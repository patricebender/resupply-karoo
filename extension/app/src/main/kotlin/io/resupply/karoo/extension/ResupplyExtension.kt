package io.resupply.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.ShowSymbols
import io.resupply.karoo.BuildConfig
import io.resupply.karoo.build.BuildController
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.build.promoteCachedRoadbook
import io.resupply.karoo.build.refetchThresholdMeters
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.PoiDatabase
import io.resupply.karoo.data.PoiQuery
import io.resupply.karoo.data.RoadbookCache
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.haversine
import io.resupply.karoo.util.locationFlow
import io.resupply.karoo.util.withKarooConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The Resupply map-layer extension.
 *
 * - `onBonusAction("build")` triggers a build for the current route/location.
 * - `startMap` observes the shared [ResupplyRepository] and draws the built POIs
 *   as map pins, redrawing whenever a build updates them.
 */
class ResupplyExtension : KarooExtension("resupply", BuildConfig.VERSION_NAME) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: ResupplyRepository
    private lateinit var configStore: ConfigStore
    private lateinit var roadbookCache: RoadbookCache
    private lateinit var query: Deferred<PoiQuery>

    // Shared, connected system service the data field streams route progress from.
    // Connected in onCreate, disconnected in onDestroy.
    private lateinit var karooSystem: KarooSystemService

    // Whether a route is currently loaded. The nearby refresher only runs route-less (a route
    // build is a deliberate one-shot along a fixed polyline). Atomic: written by the nav
    // consumer thread, read by the refresher loop.
    private val routeLoaded = java.util.concurrent.atomic.AtomicBoolean(false)
    // Identity of the last route we saw navigating (name + distance), so we can tell a genuine
    // route *change* from repeated events for the same route. Null when route-less.
    @Volatile private var lastRouteKey: String? = null
    // Lifetime consumer id for the nav-state watcher (removed in onDestroy).
    private var navConsumerId: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = ResupplyRepository.get(applicationContext)
        configStore = ConfigStore(applicationContext)
        roadbookCache = RoadbookCache.get(applicationContext)
        // PoiDatabase.get() seeds the ~310k-row Germany DB on first launch; that's far too
        // slow for the main thread (ANRs the service). Build it off-thread and hand out a
        // Deferred so a build triggered before the seed finishes awaits it instead of
        // racing an uninitialized query.
        query = scope.async { PoiQuery(PoiDatabase.get(applicationContext)) }

        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            Timber.d("karooSystem connected=$connected")
            if (connected) {
                watchNavState()
                startNearbyRefresher()
            }
        }
    }

    /**
     * Track route-loaded state for the extension's lifetime and keep the roadbook in step with
     * navigation. When a (new or changed) route loads, [realizeRoadbookForRoute] shows its cached
     * roadbook instantly or, failing that, clears to the build prompt; when navigation ends, the
     * route roadbook is dropped. Either way the overview reflects the current context with no
     * manual rebuild, and live nearby mode ends the moment a route loads (the refresher's
     * route-gate flips [nearbyLive] off). Lives here — not in [startMap] — because the map layer is
     * subscribed/cancelled as the ride view comes and goes, but this must run whenever the
     * extension is alive so the refresher's gate stays current.
     */
    private fun watchNavState() {
        navConsumerId = karooSystem.addConsumer<OnNavigationState> { event ->
            val state = event.state
            routeLoaded.set(state is OnNavigationState.NavigationState.NavigatingRoute)

            // A detour to a tapped POI (NavigatingToDestination) drops the route context from the
            // event, but the original route is still loaded underneath and resumes when the detour
            // ends. Clearing the roadbook here would cost the rider their whole roadbook +
            // favorites for a quick side-trip, then force a rebuild on rejoin. So we leave the
            // roadbook — and lastRouteKey — untouched during the detour; the fields show an "on a
            // detour" note and recover automatically on resume.
            when (state) {
                is OnNavigationState.NavigationState.NavigatingToDestination -> return@addConsumer

                is OnNavigationState.NavigationState.NavigatingRoute -> {
                    // Cheap change-detector only: name+distance is enough to tell "same route as the
                    // last event" from "a new/changed route", so we don't decode+hash the polyline on
                    // every repeated NavigatingRoute event. The authoritative route match (and the
                    // cache lookup) happens in realizeRoadbookForRoute via the polyline hash — this
                    // key just decides whether to bother. Same route reloaded keeps whatever is
                    // showing (its roadbook, or a prompt if never built).
                    val key = "${state.name}|${state.routeDistance}"
                    if (key != lastRouteKey) {
                        lastRouteKey = key
                        realizeRoadbookForRoute(state)
                    }
                }

                is OnNavigationState.NavigationState.Idle -> {
                    // Navigation truly ended (not a detour). Drop a stale route roadbook so the
                    // overview returns to its build prompt for the next context. A route-less
                    // (nearby) set stays — it's location-scoped, not route-scoped.
                    if (lastRouteKey != null && repository.pois.value.isNotEmpty()) {
                        Timber.d("navigation ended → clearing route roadbook")
                        repository.clear()
                        repository.setBuildState(BuildState.Idle)
                    }
                    lastRouteKey = null
                }
            }
        }
    }

    /**
     * A (new or changed) route just loaded: show its roadbook instantly if we've built it before.
     * Looks the route up in [RoadbookCache] by its full build key and, on a hit, promotes the
     * cached POIs into the live set with no DB query and no rebuild tap — so the data field jumps
     * straight from "Tap to build" to showing places the moment the route is on screen. On a miss
     * the previous roadbook is cleared, leaving the field's build prompt for this new context.
     *
     * Runs off the nav-consumer thread (the promotion touches DataStore/disk). A stale build state
     * is reset either way so a leftover "Success" from the last route can't linger.
     */
    private fun realizeRoadbookForRoute(state: OnNavigationState.NavigationState.NavigatingRoute) {
        scope.launch {
            val config = configStore.config.first()
            val regions = configStore.installedRegions.first()
            val hit = promoteCachedRoadbook(
                routePolyline = state.routePolyline,
                routeDistance = state.routeDistance,
                config = config,
                installedRegions = regions,
                repository = repository,
                roadbookCache = roadbookCache,
                configStore = configStore,
            )
            if (!hit) {
                // Never built (or inputs changed): drop any prior roadbook so the field prompts
                // to build for this route rather than showing the last route's places.
                if (repository.pois.value.isNotEmpty()) repository.clear()
                repository.setBuildState(BuildState.Idle)
            }
        }
    }

    /**
     * Background nearby refresh: while riding without a route, keep the nearest POIs fresh as
     * the rider moves — no manual rebuild. Runs for the extension's lifetime (NOT tied to the
     * transient map layer). Distance-driven, not a timer: refetch once the rider has moved past
     * [refetchThresholdMeters] (half the detour radius, floored). Silent — no clear/flash, no
     * notification, no BuildState churn — so it never disturbs the ride. Keeps the last set on
     * an empty result or a query failure.
     */
    private fun startNearbyRefresher() {
        scope.launch {
            var lastFetchCenter: LatLng? = null
            karooSystem.locationFlow().collect { loc ->
                if (routeLoaded.get()) {
                    // A route is loaded → not our job; reset so re-entering route-less refetches.
                    lastFetchCenter = null
                    repository.setNearbyLive(false)
                    return@collect
                }
                val here = LatLng(loc.lat, loc.lng)
                val config = configStore.config.first()

                // We're route-less with a fix and something to search → auto-refresh is active.
                // Set the "Live" flag now (not only on a changed refetch) so the badge reflects
                // "tracking you", including right after a manual nearby build. It stays true
                // until a route loads or the roadbook is cleared.
                repository.setNearbyLive(true)

                val threshold = refetchThresholdMeters(config.detourMeters, config.smartDistance)
                val moved = lastFetchCenter?.let { haversine(it, here) } ?: Double.MAX_VALUE
                if (moved < threshold) return@collect

                val pois = try {
                    withContext(Dispatchers.IO) {
                        query.await().queryNearby(here, config.detourMeters, config.smartDistance)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "nearby refresh query failed")
                    return@collect // keep last set, retry next tick (lastFetchCenter unchanged)
                }
                if (pois.isNotEmpty()) {
                    repository.setPois(pois)
                    repository.setRouteLength(0.0) // nearby: no route → strip hidden
                    Timber.d("nearby refresh: ${pois.size} POIs @ ${here.lat},${here.lng}")
                }
                // Advance the center even on an empty result so we don't re-query the DB on
                // every tick in a POI-free area; keep the last non-empty set on the map/fields.
                lastFetchCenter = here
            }
        }
    }

    /**
     * Custom data fields the rider can add to a ride page. Evaluated lazily after
     * [onCreate] so [karooSystem]/[repository]/[configStore] are ready.
     */
    override val types: List<DataTypeImpl> by lazy {
        // The all-categories rotating field, plus one single-category field per category so a
        // rider can pin just the amenity they care about. Generated from the enum so adding a
        // category needs no wiring here (only an extension_info.xml <DataType> + strings).
        listOf(
            UpcomingPoisDataType(karooSystem, repository, configStore, extension),
            FavoritePoisDataType(karooSystem, repository, configStore, extension),
        ) +
            Category.entries.map { CategoryPoiDataType(it, karooSystem, repository, configStore, extension) }
    }

    override fun onBonusAction(actionId: String) {
        if (actionId != ACTION_BUILD) return
        Timber.d("onBonusAction: build")
        scope.launch {
            withKarooConnection(applicationContext) { system ->
                BuildController(system, configStore, repository, roadbookCache, query.await()).runBuild()
            }
        }
    }

    override fun startMap(emitter: Emitter<MapEffect>) {
        Timber.d("startMap: observing roadbook POIs")
        var shownIds = emptyList<String>()

        // Draw pins whenever the built set OR the enabled-category filter changes (build,
        // clear, route-removed, background nearby refresh, or a category toggle). The build
        // holds every category in memory; only the enabled subset is drawn, so toggling a
        // category on/off adds/removes its pins instantly with no rebuild. Only the pin
        // drawing lives here — the route-removed clear and the nearby refresher are
        // extension-lifetime (see onCreate), since the map layer is subscribed/cancelled as
        // the ride view comes and goes.
        val drawJob = combine(repository.pois, configStore.config) { pois, cfg ->
            // The built set (every category) and the visible subset (enabled only). We hide
            // against the *whole* built set, not just what this session drew, so a category
            // toggled off — or pins left over from a previous map session (shownIds resets when
            // the map layer is re-subscribed) — are always cleared.
            val visible = pois.filter { cfg.showsPoi(it) }
            pois.map { it.id } to visible
        }
            .onEach { (allIds, visible) ->
                // Hide every built pin that isn't currently visible (disabled category, removed,
                // or stale from a prior session), then show the visible subset.
                val visibleIds = visible.map { it.id }.toSet()
                val toHide = (shownIds.toSet() + allIds) - visibleIds
                if (toHide.isNotEmpty()) emitter.onNext(HideSymbols(toHide.toList()))
                if (visible.isNotEmpty()) emitter.onNext(ShowSymbols(visible.map { it.toSymbol() }))
                shownIds = visibleIds.toList()
                Timber.d("map: drew ${visible.size}/${allIds.size} POIs")
            }
            .launchIn(scope)

        emitter.setCancellable {
            Timber.d("startMap: cancelled")
            drawJob.cancel()
        }
    }

    override fun onDestroy() {
        navConsumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val ACTION_BUILD = "build"
    }
}
