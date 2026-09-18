package io.resupply.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import io.resupply.karoo.BuildConfig
import io.resupply.karoo.build.BuildController
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.build.refetchThresholdMeters
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.PoiDatabase
import io.resupply.karoo.data.PoiQuery
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
     * Track route-loaded state for the extension's lifetime, and clear the roadbook whenever
     * the route context stops matching the current POIs — navigation stops (route removed), a
     * *different* route is loaded, or a route is loaded while a route-less (nearby) set is
     * showing. In every case the overview then shows its "build" prompt for the new context
     * (no stale POIs, no manual rebuild button needed). Live nearby mode ends the moment a
     * route loads (the refresher's route-gate flips [nearbyLive] off). Lives here — not in
     * [startMap] — because the map layer is subscribed/cancelled as the ride view comes and
     * goes, but this must run whenever the extension is alive so the refresher's gate stays
     * current.
     */
    private fun watchNavState() {
        navConsumerId = karooSystem.addConsumer<OnNavigationState> { event ->
            val state = event.state
            routeLoaded.set(state is OnNavigationState.NavigationState.NavigatingRoute)

            // A route's identity: name + distance. A route-less state (idle, nearby) has a null
            // key, so nearby→route is a key change too and clears the same way.
            val key = (state as? OnNavigationState.NavigationState.NavigatingRoute)
                ?.let { "${it.name}|${it.routeDistance}" }

            // Clear when the context changes and there are POIs to drop: route removed, a
            // different route loaded, or a route loaded over a nearby set (null → key). A
            // route reloaded identical to the current one (same key) keeps its roadbook.
            if (key != lastRouteKey && repository.pois.value.isNotEmpty()) {
                Timber.d("route context changed ($lastRouteKey → $key) → clearing roadbook")
                repository.clear() // also flips nearbyLive off
                repository.setBuildState(BuildState.Idle)
            }
            lastRouteKey = key
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

                val threshold = refetchThresholdMeters(config.detourMeters)
                val moved = lastFetchCenter?.let { haversine(it, here) } ?: Double.MAX_VALUE
                if (moved < threshold) return@collect

                val pois = try {
                    withContext(Dispatchers.IO) {
                        query.await().queryNearby(here, config.detourMeters)
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
        listOf(UpcomingPoisDataType(karooSystem, repository, configStore, extension)) +
            Category.entries.map { CategoryPoiDataType(it, karooSystem, repository, configStore, extension) }
    }

    override fun onBonusAction(actionId: String) {
        if (actionId != ACTION_BUILD) return
        Timber.d("onBonusAction: build")
        scope.launch {
            withKarooConnection(applicationContext) { system ->
                BuildController(system, configStore, repository, query.await()).runBuild()
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
            val visible = pois.filter { Category.ofType(it.type) in cfg.enabledCategories }
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

    private fun Poi.toSymbol(): Symbol.POI = Symbol.POI(
        id = id,
        lat = lat,
        lng = lng,
        type = symbolType(type),
        name = name,
        distancesAlongRoute = distancesAlongRoute,
    )

    /**
     * Map our POI type strings to karoo-ext [Symbol.POI.Types] constants (which
     * are lowercase). A mismatch renders the generic pin icon, so this must stay
     * aligned with the DB's `type` values.
     */
    private fun symbolType(type: String): String = when (type) {
        "COFFEE" -> Symbol.POI.Types.COFFEE
        "FOOD" -> Symbol.POI.Types.FOOD
        "BAR" -> Symbol.POI.Types.BAR
        "CONVENIENCE_STORE" -> Symbol.POI.Types.CONVENIENCE_STORE
        "REST_STOP" -> Symbol.POI.Types.WATER      // drinking water → water icon
        "RESTROOM" -> Symbol.POI.Types.RESTROOM
        "BIKE_SHOP" -> Symbol.POI.Types.BIKE_SHOP
        "GAS_STATION" -> Symbol.POI.Types.GAS_STATION
        "ICE_CREAM" -> Symbol.POI.Types.FOOD       // no ice-cream pin in karoo-ext → food icon
        "LODGING" -> Symbol.POI.Types.LODGING
        else -> Symbol.POI.Types.GENERIC
    }

    private companion object {
        const val ACTION_BUILD = "build"
    }
}
