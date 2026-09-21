package io.resupply.karoo.build

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.SystemNotification
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.PoiQuery
import io.resupply.karoo.data.RoadbookCache
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.awaitOnce
import io.resupply.karoo.util.cumulativeDistances
import io.resupply.karoo.util.decodeLatLng
import io.resupply.karoo.util.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Orchestrates a roadbook build, shared by both triggers (in-app button and the
 * in-ride BonusAction). Reads config, resolves the route (or location), and
 * queries the on-device POI database — no backend, works fully offline.
 */
class BuildController(
    private val system: KarooSystemService,
    private val configStore: ConfigStore,
    private val repository: ResupplyRepository,
    private val roadbookCache: RoadbookCache,
    private val query: PoiQuery,
) {
    /** Run a build. Serialized so overlapping triggers can't race. */
    suspend fun runBuild(): BuildState = mutex.withLock {
        val config = configStore.config.first()
        val installedRegions = configStore.installedRegions.first()

        return try {
            // Resolve the route (nav state may not emit if unchanged; bound the wait).
            val nav = withTimeoutOrNull(NAV_READ_TIMEOUT_MS) {
                system.awaitOnce<OnNavigationState>()
            }?.state

            when (nav) {
                is OnNavigationState.NavigationState.NavigatingRoute -> {
                    val buildKey = routeBuildKey(nav.routePolyline, config, installedRegions)

                    // Cache hit: this route with these inputs was built before — reuse the stored
                    // set and skip the DB query. Promotion re-derives this route's favorites, so
                    // its stars come back too.
                    val cached = roadbookCache.get(buildKey)
                    if (cached != null && cached.isNotEmpty()) {
                        Timber.d("build cache hit: reusing ${cached.size} POIs for route")
                        promoteRoadbook(cached, nav.routePolyline, nav.routeDistance, repository, configStore)
                        return@withLock succeed(cached.size, byCategory(cached))
                    }

                    // Miss: recompute. Clear only now so a hit above kept the held set intact.
                    repository.clear()
                    publish(BuildState.Building())
                    val route = decodeLatLng(nav.routePolyline)
                    Timber.d("build along route: ${route.size} pts, detour=${config.detourMeters}, smart=${config.smartDistance}")
                    // Cache the route length so the Waybook strip can place POI dots.
                    repository.setRouteLength(cumulativeDistances(route).lastOrNull() ?: 0.0)
                    val pois = withContext(Dispatchers.IO) {
                        query.queryCorridor(route, config.detourMeters, config.smartDistance)
                    }
                    // Point the derived favorites at this route (its stars, if any, reappear;
                    // showsPoi decides which are visible under the current categories/radius).
                    configStore.setCurrentRouteKey(sha256(nav.routePolyline))
                    if (pois.isNotEmpty()) roadbookCache.put(buildKey, pois)
                    finish(pois)
                }
                else -> {
                    // No route: build around the rider. This needs a location fix — with no
                    // satellite reception the Karoo may have none, so tell the rider we're
                    // waiting on GPS rather than sitting on the generic "Searching…".
                    // Nearby has no route → no favorites (derived set is empty).
                    repository.clear()
                    configStore.clearCurrentRouteKey()
                    publish(BuildState.Building("Waiting for GPS…"))
                    val loc = withTimeoutOrNull(LOCATION_READ_TIMEOUT_MS) {
                        system.awaitOnce<OnLocationChanged>()
                    } ?: return fail("No GPS signal - try again later")
                    Timber.d("build nearby: ${loc.lat},${loc.lng}")
                    repository.setRouteLength(0.0) // nearby: no route → strip hidden
                    publish(BuildState.Building()) // fix acquired → back to "Searching…"
                    val pois = withContext(Dispatchers.IO) {
                        query.queryNearby(LatLng(loc.lat, loc.lng), config.detourMeters, config.smartDistance)
                    }
                    finish(pois) // nearby sets are never cached
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "build failed")
            fail(e.message ?: "Build failed")
        }
    }

    /** Show the freshly-queried [pois] and publish the terminal build state. */
    private fun finish(pois: List<Poi>): BuildState {
        repository.setPois(pois)
        return if (pois.isEmpty()) {
            // Likely outside an installed region — guide the user rather than an empty success.
            fail("No POIs here — download this region?")
        } else {
            succeed(pois.size, byCategory(pois))
        }
    }

    private fun byCategory(pois: List<Poi>): Map<Category, Int> =
        pois.mapNotNull { Category.ofType(it.type) }.groupingBy { it }.eachCount()

    private fun succeed(count: Int, byCategory: Map<Category, Int>): BuildState {
        val state = BuildState.Success(count, byCategory, System.currentTimeMillis())
        publish(state)
        notify("Resupply: $count POIs found")
        return state
    }

    private fun fail(message: String): BuildState {
        val state = BuildState.Error(message)
        publish(state)
        notify("Resupply: $message")
        return state
    }

    private fun publish(state: BuildState) = repository.setBuildState(state)

    private fun notify(message: String) {
        system.dispatch(
            SystemNotification(id = UUID.randomUUID().toString(), message = message),
        )
    }

    private companion object {
        const val NAV_READ_TIMEOUT_MS = 5_000L
        // A location fix can take longer than a nav-state read — a cold GPS lock isn't
        // instant. Wait longer before giving up so a rider who just powered on isn't told
        // "no fix" prematurely, but not so long the build appears hung with no reception.
        const val LOCATION_READ_TIMEOUT_MS = 15_000L
        // One build at a time across the whole process (app + BonusAction).
        val mutex = Mutex()
    }
}
