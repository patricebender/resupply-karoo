package io.resupply.karoo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import io.resupply.karoo.build.BuildController
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.CorridorTuning
import io.resupply.karoo.data.PlacesClient
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.PoiDatabase
import io.resupply.karoo.data.PoiQuery
import io.resupply.karoo.data.Region
import io.resupply.karoo.data.RegionCatalog
import io.resupply.karoo.data.RegionCatalogClient
import io.resupply.karoo.data.RegionDownloadStatus
import io.resupply.karoo.data.RegionManifestEntry
import io.resupply.karoo.data.ResupplyConfig
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.data.RoadbookCache
import io.resupply.karoo.data.RouteState
import io.resupply.karoo.data.WikipediaClient
import io.resupply.karoo.data.toRouteState
import io.resupply.karoo.extension.toSymbol
import io.resupply.karoo.service.RegionDownloadService
import io.resupply.karoo.ui.SettingsScreen
import io.resupply.karoo.ui.theme.ResupplyTheme
import io.resupply.karoo.ui.PoiDetailScreen
import io.resupply.karoo.ui.RegionsScreen
import io.resupply.karoo.ui.WaybookScreen
import io.resupply.karoo.ui.WelcomeScreen
import io.resupply.karoo.util.Connectivity
import io.resupply.karoo.ui.field.ACTION_BUILD
import io.resupply.karoo.ui.field.EXTRA_ACTION
import io.resupply.karoo.ui.hoursFor
import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.awaitOnce
import io.resupply.karoo.util.locationFlow
import io.resupply.karoo.util.navStateFlow
import io.resupply.karoo.util.streamDataFlow
import io.resupply.karoo.util.withKarooConnection
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** In-app screens. No nav framework — a small sealed state the host switches on. */
private sealed interface Screen {
    data object Waybook : Screen
    data object Settings : Screen
    data object Regions : Screen
    data object Welcome : Screen
    data class Detail(val poiId: String) : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var repository: ResupplyRepository
    private lateinit var roadbookCache: RoadbookCache
    private lateinit var query: Deferred<PoiQuery>

    // Live route progress for the overview: a long-lived connection streaming
    // DISTANCE_TO_DESTINATION so the Waybook timeline can show where the rider is and
    // the list can show how far each POI is ahead. Best-effort — null distance means
    // "no live position" and the screen renders as it did before.
    private val progressSystem by lazy { KarooSystemService(applicationContext) }
    private val toDestMeters = MutableStateFlow<Double?>(null)
    // Rider's ride-average speed (m/s), followed live off the same connection. Turns a POI's
    // distance-ahead into an estimated arrival time for the "open on arrival" list cue. Average
    // (not instantaneous) speed because a stop is kilometres out — the whole-ride pace predicts
    // arrival far better than a momentary value. Null until the first sample.
    private val avgSpeedMps = MutableStateFlow<Double?>(null)
    // Whether a route is loaded (and its name/distance), followed live off the same
    // connection. Drives the landing screen's route hero vs "load a route" explainer and
    // gates the build action — a roadbook only makes sense along a route.
    private val routeState = MutableStateFlow<RouteState>(RouteState.Unknown)
    // Rider location, followed live off the same connection. Powers the overview's nearby
    // list distances (straight-line to each POI) when the current set is a /nearby build.
    private val riderLocation = MutableStateFlow<LatLng?>(null)
    private val regionCatalog: List<Region> by lazy { RegionCatalog.load(applicationContext) }

    // Region manifest (sizes/counts) + its fetch state. The download itself lives in the
    // foreground RegionDownloadService, observed via its liveDownload flow + persisted status.
    private val regionManifest =
        androidx.compose.runtime.mutableStateOf<Map<String, RegionManifestEntry>>(emptyMap())
    private val manifestLoading = androidx.compose.runtime.mutableStateOf(false)
    private val manifestFailed = androidx.compose.runtime.mutableStateOf(false)

    // Bumped on every fresh entry from the data field (onCreate + onNewIntent). The app is
    // singleTop, so a re-tap re-uses this Activity and the composition survives — observing
    // this tick lets the overview re-arm its "jump to current position" scroll each time,
    // even though the rider may have scrolled the list meanwhile.
    private val entryTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(applicationContext)
        repository = ResupplyRepository.get(applicationContext)
        roadbookCache = RoadbookCache.get(applicationContext)
        // Seeding the bundled DB on first launch is too slow for the main thread; build the
        // query off-thread and await it where a build actually needs it. The same async
        // opens (and, first run / after a version bump, seeds) the DB, so we reconcile the
        // installed-region set from it once it's ready — see reconcileInstalledRegions.
        query = lifecycleScope.async(Dispatchers.IO) { PoiQuery(PoiDatabase.get(applicationContext)) }
        lifecycleScope.launch(Dispatchers.IO) {
            query.await() // ensure the DB is opened + seeded before reading its region_ids
            reconcileInstalledRegions()
        }

        // Follow live route progress for the overview. One connection for the activity's
        // lifetime; the stream feeds toDestMeters, which the Waybook screen turns into a
        // position once it also knows the route length.
        progressSystem.connect { connected ->
            if (!connected) return@connect
            lifecycleScope.launch {
                progressSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION).collect { state ->
                    toDestMeters.value = (state as? StreamState.Streaming)
                        ?.dataPoint?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION)
                }
            }
            lifecycleScope.launch {
                progressSystem.navStateFlow().collect { routeState.value = it.toRouteState() }
            }
            lifecycleScope.launch {
                progressSystem.locationFlow().collect { riderLocation.value = LatLng(it.lat, it.lng) }
            }
            lifecycleScope.launch {
                progressSystem.streamDataFlow(DataType.Type.AVERAGE_SPEED).collect { state ->
                    avgSpeedMps.value = (state as? StreamState.Streaming)
                        ?.dataPoint?.values?.get(DataType.Field.AVERAGE_SPEED)
                }
            }
        }

        // Launched from a "Tap to build" / "Tap for nearby" data field: kick off a build (see
        // buildFromField for the route/nearby/no-fix decision). Always land on Waybook (not
        // Settings): it carries the build feedback itself, and shows the NearbyReadyState
        // prompt when a build isn't started for want of a GPS fix.
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_BUILD) buildFromField()

        setContent {
            // Theme is driven by the persisted mode; collect it here so the very first frame is
            // already the right theme (initialValue = default SYSTEM) and there's no purple flash
            // before the config flow emits. One ResupplyTheme at the root wraps the whole app.
            val config by configStore.config.collectAsStateWithLifecycle(initialValue = ResupplyConfig())
            ResupplyTheme(config.themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ResupplyApp(config = config, initialScreen = Screen.Waybook)
                }
            }
        }
    }

    // singleTop: a re-tap of the field lands here instead of a fresh onCreate. Re-handle
    // the build extra and bump the entry tick so the overview snaps back to the rider's
    // current position (the composition — and its scroll guard — outlived the last visit).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_ACTION) == ACTION_BUILD) buildFromField()
        entryTick.intValue++
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { progressSystem.disconnect() }
    }

    @Composable
    private fun ResupplyApp(config: ResupplyConfig, initialScreen: Screen = Screen.Waybook) {
        val buildState by repository.buildState.collectAsStateWithLifecycle()
        val pois by repository.pois.collectAsStateWithLifecycle()
        val routeLength by repository.routeLengthMeters.collectAsStateWithLifecycle()
        val toDest by toDestMeters.collectAsStateWithLifecycle()
        val avgSpeed by avgSpeedMps.collectAsStateWithLifecycle()
        val route by routeState.collectAsStateWithLifecycle()
        val rider by riderLocation.collectAsStateWithLifecycle()
        val nearbyLive by repository.nearbyLive.collectAsStateWithLifecycle()
        val nearbyPaused by repository.nearbyPaused.collectAsStateWithLifecycle()
        // Live position along the route, mirroring the field's math
        // (UpcomingPoisDataType): route length − distance-to-destination. Null when we
        // have no route or no live stream — the overview then drops the position cues.
        val progressMeters: Double? = toDest?.takeIf { routeLength > 0.0 }
            ?.let { (routeLength - it).coerceIn(0.0, routeLength) }

        // The nearby list/radar must scale to the query's ACTUAL reach, not the raw setting:
        // in smart mode the query fetches out to CorridorTuning.maxReachMeters (≫ detourMeters),
        // so a fixed detourMeters here would drop POIs the query surfaced and mis-scale the radar.
        val nearbyRadiusMeters = if (config.smartDistance) {
            CorridorTuning.maxReachMeters(config.detourMeters, smart = true)
        } else {
            config.detourMeters
        }

        var screen: Screen by remember { mutableStateOf(initialScreen) }

        // First-run onboarding: if nothing is installed and the welcome hasn't been shown, land
        // on it once. Gated on a persisted flag, not just emptiness, so it never reappears after
        // the rider removes all their regions.
        val onboardingSeen by configStore.onboardingSeen.collectAsStateWithLifecycle(initialValue = true)
        val installedForOnboarding by configStore.installedRegions.collectAsStateWithLifecycle(initialValue = emptySet())
        LaunchedEffect(onboardingSeen, installedForOnboarding) {
            if (!onboardingSeen && installedForOnboarding.isEmpty()) {
                screen = Screen.Welcome
            }
        }

        // Hoisted here so the list scroll position is preserved across navigation to
        // the detail/filter screens and back.
        val waybookListState = rememberLazyListState()
        // Position-following flag, hoisted so it SURVIVES a POI-detail round-trip (the Waybook
        // screen leaves the composition there, so a flag living inside it would reset to true on
        // return and yank the rider back to the current position). Only a fresh field-tap
        // (entryTick bump, below) re-arms it; a manual scroll in the list clears it.
        var followPosition by remember { mutableStateOf(true) }

        // Each fresh field-tap (entryTick bump) re-engages position-following and returns to
        // the overview: the rider wants "what's next from here", not wherever they'd scrolled.
        // Skips the initial composition (tick 0) so first launch keeps initialScreen.
        val tick = entryTick.intValue
        LaunchedEffect(tick) {
            if (tick == 0) return@LaunchedEffect
            followPosition = true
            screen = Screen.Waybook
        }

        when (val s = screen) {
            is Screen.Waybook -> WaybookScreen(
                pois = pois,
                enabledCategories = config.enabledCategories,
                safeWaterOnly = config.safeWaterOnly,
                routeLengthMeters = routeLength,
                progressMeters = progressMeters,
                avgSpeedMps = avgSpeed,
                routeState = route,
                riderLocation = rider,
                nearbyLive = nearbyLive,
                nearbyPaused = nearbyPaused,
                nearbyRadiusMeters = nearbyRadiusMeters,
                buildState = buildState,
                favoritePoiIds = config.favoritePoiIds,
                favoritesOnly = config.favoritesOnly,
                onToggleFavorite = { poi, fav ->
                    lifecycleScope.launch { configStore.setFavorite(poi.id, fav) }
                },
                onToggleFavoritesFilter = {
                    lifecycleScope.launch { configStore.setFavoritesOnly(!config.favoritesOnly) }
                },
                onBuild = ::runBuild,
                onOpenSettings = { screen = Screen.Settings },
                onOpenPoi = { screen = Screen.Detail(it.id) },
                following = followPosition,
                onUserScrolled = { followPosition = false },
                // OSM hours, or a Google result already fetched this session → badge in list.
                hoursOf = { poi -> hoursFor(poi, repository.cachedHours(poi.id)?.hours) },
                listState = waybookListState,
            )

            is Screen.Settings -> {
                val installed by configStore.installedRegions
                    .collectAsStateWithLifecycle(initialValue = emptySet())
                SettingsScreen(
                    config = config,
                    buildState = buildState,
                    // No route = live (nearby) mode. Route mode gets rebuild + trashcan; live mode
                    // gets the "Stop live search" control instead. Gated on the route (not
                    // nearbyLive) so the mode is stable from the moment there's no route.
                    routeLoaded = route is RouteState.Loaded,
                    // Live search on/off reflects the rider's intent (on unless paused); hasFix
                    // gates whether it can actually run. Together they drive the 3-state toggle.
                    liveOn = !nearbyPaused,
                    hasFix = rider != null,
                    pois = pois,
                    installedSummary = installedSummary(installed),
                    onDetourChange = { m -> lifecycleScope.launch { configStore.setDetour(m) } },
                    onCategoryToggle = { c, on ->
                        lifecycleScope.launch { configStore.setCategoryEnabled(c, on) }
                    },
                    onSafeWaterToggle = { on ->
                        lifecycleScope.launch { configStore.setSafeWaterOnly(on) }
                    },
                    onSmartDistanceToggle = { on ->
                        lifecycleScope.launch { configStore.setSmartDistance(on) }
                    },
                    themeMode = config.themeMode,
                    onThemeModeChange = { mode ->
                        lifecycleScope.launch { configStore.setThemeMode(mode) }
                    },
                    onBuild = ::runBuild,
                    onClear = {
                        repository.clear()
                        repository.setBuildState(BuildState.Idle)
                        lifecycleScope.launch {
                            // The trashcan is a full reset: forget this route's cached places AND
                            // its saved favorites so a rebuild starts genuinely fresh.
                            configStore.currentRouteKey()?.let { routeKey ->
                                roadbookCache.removeRoute(routeKey)
                                configStore.forgetRouteFavorites(routeKey)
                            }
                            configStore.clearCurrentRouteKey()
                        }
                    },
                    onToggleLive = { turnOn ->
                        if (turnOn) {
                            // Resume: a fresh nearby build (which clears the pause) repopulates and
                            // the refresher/ticker take over tracking again.
                            runBuild()
                        } else {
                            // Pause → pre-search state (POIs cleared, not live, refresher stood
                            // down). The overview falls back to its Find invitation.
                            repository.pauseNearby()
                        }
                    },
                    onOpenRegions = { screen = Screen.Regions },
                    onBack = { screen = Screen.Waybook },
                )
            }

            is Screen.Welcome -> WelcomeScreen(
                onChooseRegion = {
                    lifecycleScope.launch { configStore.setOnboardingSeen() }
                    screen = Screen.Regions
                },
                onSkip = {
                    lifecycleScope.launch { configStore.setOnboardingSeen() }
                    screen = Screen.Waybook
                },
            )

            is Screen.Regions -> {
                val installed by configStore.installedRegions
                    .collectAsStateWithLifecycle(initialValue = emptySet())
                val onWifi by Connectivity.wifiFlow(applicationContext)
                    .collectAsStateWithLifecycle(initialValue = Connectivity.isOnWifi(applicationContext))
                val live by RegionDownloadService.liveDownload.collectAsStateWithLifecycle()
                val statuses by configStore.downloadStatuses.collectAsStateWithLifecycle(initialValue = emptyList())
                val failed = remember(statuses) {
                    statuses.filter { it.phase == RegionDownloadStatus.Phase.FAILED }
                        .associate { it.regionId to (it.reason ?: "Download failed") }
                }
                // Fetch the manifest once on entry (for sizes/counts) if we don't have it.
                LaunchedEffect(Unit) {
                    if (regionManifest.value.isEmpty()) loadManifest()
                }
                RegionsScreen(
                    regions = regionCatalog,
                    manifest = regionManifest.value,
                    installedRegions = installed,
                    onWifi = onWifi,
                    manifestLoading = manifestLoading.value,
                    manifestFailed = manifestFailed.value,
                    live = live,
                    failedRegionIds = failed,
                    removingRegionId = removingRegion.value,
                    onDownload = ::downloadRegion,
                    onRemove = ::removeRegion,
                    onBack = { screen = Screen.Waybook },
                )
            }

            is Screen.Detail -> {
                val poi = pois.firstOrNull { it.id == s.poiId }
                if (poi == null) {
                    // POI vanished (e.g. cleared while open) — bounce back.
                    screen = Screen.Waybook
                } else {
                    // Offer a Google hours lookup only when OSM has none, the category
                    // is one where hours matter, and an API key is configured.
                    val googleEligible = poi.tags["opening_hours"] == null &&
                        Category.ofType(poi.type) in GOOGLE_HOURS_CATEGORIES &&
                        BuildConfig.PLACES_API_KEY.isNotEmpty()
                    // "Open on arrival" for the detail view: the estimated arrival time at this
                    // POI, same math as the list row (distance-ahead + average speed). Only for a
                    // POI still ahead on a route; null otherwise → the detail omits the ETA.
                    val detailArrival = progressMeters
                        ?.let { io.resupply.karoo.data.aheadMetersFor(poi, it) }
                        ?.let { io.resupply.karoo.data.etaArrival(it, avgSpeed) }
                    PoiDetailScreen(
                        poi = poi,
                        hasRoute = routeLength > 0,
                        arrival = detailArrival,
                        cachedDescription = repository.cachedDescription(poi.id),
                        loadDescription = { fetchDescription(poi) },
                        cachedGoogleHours = repository.cachedHours(poi.id),
                        loadGoogleHours = if (googleEligible) {
                            { fetchGoogleHours(poi) }
                        } else {
                            null
                        },
                        canFavorite = routeLength > 0,
                        isFavorite = poi.id in config.favoritePoiIds,
                        onToggleFavorite = { fav ->
                            lifecycleScope.launch { configStore.setFavorite(poi.id, fav) }
                        },
                        onNavigate = { navigateToPoi(poi) },
                        onBack = { screen = Screen.Waybook },
                    )
                }
            }
        }
    }

    /**
     * Start navigation to [poi] the same way tapping its map pin does: dispatch a
     * [LaunchPinDrop] with the POI's symbol. The Karoo drops a pin and routes to it as a
     * detour; the loaded route stays underneath and resumes on arrival (the extension's
     * nav watcher treats NavigatingToDestination as route-preserving). Then background the
     * app so the map/navigation comes forward — moveTaskToBack (not finish) keeps this
     * singleTop activity and its composition alive for the next field tap.
     */
    private fun navigateToPoi(poi: Poi) {
        progressSystem.dispatch(LaunchPinDrop(poi.toSymbol()))
        moveTaskToBack(true)
    }

    /**
     * Build on a data-field tap ([ACTION_BUILD]). A route build if one is loaded, otherwise a
     * nearby build around the rider — the field prompt ("Tap for nearby") made that intent
     * explicit, so this is never a surprise search. [BuildController] itself picks route vs
     * nearby from the live nav state; we just trigger it.
     *
     * One case we *don't* auto-build: no route AND no location fix. A nearby build would just
     * spin on "Waiting for GPS…" and fail, so we drop the rider on the overview instead — its
     * [NearbyReadyState] shows a "Waiting for GPS signal" face with the Find button disabled
     * until a fix lands, at which point the button enables and they can search.
     *
     * The observed [routeState]/[riderLocation] flows are still at their initial values at
     * intent time (the [progressSystem] connection hasn't emitted yet on a cold launch), so we
     * can't read them synchronously here. Resolve both once over a short-lived connection,
     * bounded so a tap never hangs, then decide.
     */
    private fun buildFromField() = lifecycleScope.launch {
        val hasRoute = routeState.value is RouteState.Loaded
        val hasFix = riderLocation.value != null
        // Fast path: a flow already settled to a usable value → build without re-resolving.
        if (hasRoute || hasFix) return@launch runBuild()

        val resolved = withKarooConnection(applicationContext) { system ->
            val route = withTimeoutOrNull(STATE_READ_TIMEOUT_MS) {
                system.awaitOnce<OnNavigationState>()
            }?.state?.toRouteState()
            if (route is RouteState.Loaded) return@withKarooConnection true
            // No route: only build if a location fix is actually available.
            withTimeoutOrNull(STATE_READ_TIMEOUT_MS) {
                system.awaitOnce<OnLocationChanged>()
            } != null
        } ?: false

        // Only build once we have a route or a fix. With neither, do nothing here: the overview's
        // "Waiting for GPS signal" face (driven by the live riderLocation flow) carries the state
        // and enables its Find button the moment a fix arrives — no error, no timeout to explain.
        if (resolved) runBuild()
    }

    /** Build from the app by spinning up a short-lived Karoo connection. */
    private fun runBuild() {
        // A manual build is the rider starting a search again — lift any live-search pause so the
        // refresher resumes tracking once this build lands (route-less case).
        repository.setNearbyPaused(false)
        repository.setBuildState(BuildState.Building("Connecting…"))
        val system = KarooSystemService(applicationContext)
        system.connect { connected ->
            if (!connected) {
                repository.setBuildState(BuildState.Error("Karoo not connected"))
                return@connect
            }
            lifecycleScope.launch {
                BuildController(system, configStore, repository, roadbookCache, query.await()).runBuild()
                system.disconnect()
            }
        }
    }

    /**
     * Fetch a place description via the Karoo HTTP bridge (works over the paired
     * phone, not just WiFi), caching the result so re-opening is instant and it
     * survives offline. Uses a short-lived connection like [runBuild].
     */
    private suspend fun fetchDescription(poi: Poi): String? {
        repository.cachedDescription(poi.id)?.let { return it }
        return withKarooConnection(applicationContext) { system ->
            WikipediaClient(system).summaryFor(poi.tags["wikipedia"])
                ?.also { repository.cacheDescription(poi.id, it) }
        }
    }

    /**
     * Live opening-hours lookup via Google Places (only when OSM has none). The Place
     * ID is cached (allowed by Maps ToS); the hours are returned for display but never
     * persisted. Short-lived connection like [fetchDescription].
     */
    private suspend fun fetchGoogleHours(poi: Poi): PlacesClient.Result? {
        // Serve a still-fresh cached fetch (performance cache) without a network call.
        repository.cachedHours(poi.id)?.let { return it }
        return withKarooConnection(applicationContext) { system ->
            PlacesClient(system)
                .hoursFor(
                    name = poi.name,
                    lat = poi.lat,
                    lng = poi.lng,
                    knownPlaceId = repository.cachedPlaceId(poi.id),
                )
                ?.also {
                    repository.cachePlaceId(poi.id, it.placeId) // Place ID: persisted
                    repository.cacheHours(poi.id, it)           // hours: memory, short TTL
                }
        }
    }

    /**
     * A short "what's installed" line for the Settings → Data row. The set is reconciled
     * from the DB, so it's exactly what's installed — empty only on the lean edition before
     * any download. One or two labels are spelled out; more collapse to "France +N".
     */
    private fun installedSummary(installed: Set<String>): String {
        if (installed.isEmpty()) return "No regions yet — download one"
        val labels = installed.mapNotNull { id -> regionCatalog.firstOrNull { it.id == id }?.label }
        if (labels.isEmpty()) return "No regions yet — download one"
        return when {
            labels.size <= 2 -> labels.sorted().joinToString(", ")
            else -> "${labels.sorted().first()} +${labels.size - 1}"
        }
    }

    /**
     * Make the persisted installed-region set exactly match the live DB (the source of
     * truth). Run once at startup after the DB is opened/seeded, this subsumes: the
     * first-run record of the bundled seed's regions, the reset after a version-bump reseed
     * (which drops downloaded regions), and recovery from a crash mid-install. Without it,
     * config could claim regions the DB no longer has, or miss the bundled ones.
     */
    private suspend fun reconcileInstalledRegions() {
        val dbRegions = PoiDatabase.installedRegionIdsFromDb(applicationContext)
        if (dbRegions != configStore.installedRegions.first()) {
            Timber.d("reconciling installed regions to DB: $dbRegions")
            configStore.setInstalledRegions(dbRegions)
        }
    }

    /** Fetch the region manifest for per-region download sizes/place counts (bridge, small). */
    private fun loadManifest() {
        manifestLoading.value = true
        manifestFailed.value = false
        lifecycleScope.launch {
            val manifest = withKarooConnection(applicationContext) { system ->
                RegionCatalogClient(system).fetchManifest()
            }
            manifestLoading.value = false
            if (manifest == null) {
                manifestFailed.value = true
            } else {
                regionManifest.value = manifest.regions.associateBy { it.id }
            }
        }
    }

    /**
     * Start downloading [region] in the foreground [RegionDownloadService] (direct WiFi
     * transport). The service owns progress/state — it survives the picker closing and the
     * screen sleeping — and the UI observes its live flow + the persisted status.
     */
    private fun downloadRegion(region: Region) {
        RegionDownloadService.start(applicationContext, region.id, region.label)
    }

    /** Remove an installed region's POIs, then drop it from the installed set. */
    private fun removeRegion(region: Region) {
        removingRegion.value = region.id
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                PoiDatabase.removeRegion(applicationContext, region.id)
            }
            configStore.removeInstalledRegion(region.id)
            configStore.clearDownloadStatus(region.id)
            removingRegion.value = null
        }
    }

    private val removingRegion = androidx.compose.runtime.mutableStateOf<String?>(null)

    private companion object {
        // Bounded wait for the nav/location state on a field-tap build decision, so a tap
        // never hangs while the Karoo connection settles.
        const val STATE_READ_TIMEOUT_MS = 5_000L

        // Categories where opening hours matter enough to spend a Google lookup.
        val GOOGLE_HOURS_CATEGORIES = setOf(
            Category.SUPERMARKETS, Category.CAFE_BAR, Category.RESTAURANTS, Category.FUEL,
            Category.ICE_CREAM, Category.HOTELS, Category.BIKE,
        )
    }
}
