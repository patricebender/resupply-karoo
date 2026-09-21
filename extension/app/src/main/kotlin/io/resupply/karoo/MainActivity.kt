package io.resupply.karoo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
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
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import io.resupply.karoo.build.BuildController
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.PlacesClient
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.PoiDatabase
import io.resupply.karoo.data.PoiQuery
import io.resupply.karoo.data.Region
import io.resupply.karoo.data.RegionCatalog
import io.resupply.karoo.data.RegionCatalogClient
import io.resupply.karoo.data.RegionManifestEntry
import io.resupply.karoo.data.ResupplyConfig
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.data.RoadbookCache
import io.resupply.karoo.data.RouteState
import io.resupply.karoo.data.WikipediaClient
import io.resupply.karoo.data.toRouteState
import io.resupply.karoo.ui.SettingsScreen
import io.resupply.karoo.ui.PoiDetailScreen
import io.resupply.karoo.ui.RegionDownloadState
import io.resupply.karoo.ui.RegionsScreen
import io.resupply.karoo.ui.WaybookScreen
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

/** In-app screens. No nav framework — a small sealed state the host switches on. */
private sealed interface Screen {
    data object Waybook : Screen
    data object Settings : Screen
    data object Regions : Screen
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
    // Whether a route is loaded (and its name/distance), followed live off the same
    // connection. Drives the landing screen's route hero vs "load a route" explainer and
    // gates the build action — a roadbook only makes sense along a route.
    private val routeState = MutableStateFlow<RouteState>(RouteState.Unknown)
    // Rider location, followed live off the same connection. Powers the overview's nearby
    // list distances (straight-line to each POI) when the current set is a /nearby build.
    private val riderLocation = MutableStateFlow<LatLng?>(null)
    private val regionCatalog: List<Region> by lazy { RegionCatalog.load(applicationContext) }

    // Region download state, hoisted so it survives navigation between screens.
    private val downloadState =
        androidx.compose.runtime.mutableStateOf<RegionDownloadState>(RegionDownloadState.Idle)
    private val regionManifest =
        androidx.compose.runtime.mutableStateOf<Map<String, RegionManifestEntry>>(emptyMap())

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
        // Seeding the ~310k-row Germany DB on first launch is too slow for the main thread;
        // build the query off-thread and await it where a build actually needs it.
        query = lifecycleScope.async(Dispatchers.IO) { PoiQuery(PoiDatabase.get(applicationContext)) }

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
        }

        // Launched from a "Tap to build" / "Tap for nearby" data field: kick off a build (see
        // buildFromField for the route/nearby/no-fix decision). Always land on Waybook (not
        // Settings): it carries the build feedback itself, and shows the NearbyReadyState
        // prompt when a build isn't started for want of a GPS fix.
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_BUILD) buildFromField()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ResupplyApp(initialScreen = Screen.Waybook)
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
    private fun ResupplyApp(initialScreen: Screen = Screen.Waybook) {
        val config by configStore.config.collectAsStateWithLifecycle(initialValue = ResupplyConfig())
        val buildState by repository.buildState.collectAsStateWithLifecycle()
        val pois by repository.pois.collectAsStateWithLifecycle()
        val routeLength by repository.routeLengthMeters.collectAsStateWithLifecycle()
        val toDest by toDestMeters.collectAsStateWithLifecycle()
        val route by routeState.collectAsStateWithLifecycle()
        val rider by riderLocation.collectAsStateWithLifecycle()
        val nearbyLive by repository.nearbyLive.collectAsStateWithLifecycle()
        // Live position along the route, mirroring the field's math
        // (UpcomingPoisDataType): route length − distance-to-destination. Null when we
        // have no route or no live stream — the overview then drops the position cues.
        val progressMeters: Double? = toDest?.takeIf { routeLength > 0.0 }
            ?.let { (routeLength - it).coerceIn(0.0, routeLength) }

        var screen: Screen by remember { mutableStateOf(initialScreen) }
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
                routeState = route,
                riderLocation = rider,
                nearbyLive = nearbyLive,
                nearbyRadiusMeters = config.detourMeters,
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
                    onOpenRegions = { screen = Screen.Regions },
                    onBack = { screen = Screen.Waybook },
                )
            }

            is Screen.Regions -> {
                val installed by configStore.installedRegions
                    .collectAsStateWithLifecycle(initialValue = emptySet())
                // Fetch the manifest once on entry (unless a download is mid-flight).
                LaunchedEffect(Unit) {
                    if (regionManifest.value.isEmpty() &&
                        downloadState.value !is RegionDownloadState.Downloading
                    ) {
                        loadManifest()
                    }
                }
                RegionsScreen(
                    regions = regionCatalog,
                    manifest = regionManifest.value,
                    // A fresh install has the Germany seed but an empty set (no download
                    // written); show the seed as installed without a first-run write.
                    installedRegions = installed.ifEmpty { setOf(Region.SEED_REGION_ID) },
                    state = downloadState.value,
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
                    PoiDetailScreen(
                        poi = poi,
                        hasRoute = routeLength > 0,
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
                        onBack = { screen = Screen.Waybook },
                    )
                }
            }
        }
    }

    /**
     * Build on a data-field tap ([ACTION_BUILD]). A route build if one is loaded, otherwise a
     * nearby build around the rider — the field prompt ("Tap for nearby") made that intent
     * explicit, so this is never a surprise search. [BuildController] itself picks route vs
     * nearby from the live nav state; we just trigger it.
     *
     * One case we *don't* auto-build: no route AND no location fix. A nearby build would just
     * spin on "Waiting for GPS…" and fail, so we drop the rider on the overview instead (its
     * [NearbyReadyState] with a manual "Find places nearby" button) — they can retry once a
     * fix lands rather than watching an auto-build fail.
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

        if (resolved) runBuild()
        // else: no route and no fix → do nothing; the overview's NearbyReadyState lets the
        // rider retry manually once GPS is available.
    }

    /** Build from the app by spinning up a short-lived Karoo connection. */
    private fun runBuild() {
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
     * A short "what's installed" line for the Settings → Data row. Empty means the
     * untouched bundled seed (Germany), same as the picker's empty-set handling. One or
     * two labels are spelled out; more collapse to "Germany +N".
     */
    private fun installedSummary(installed: Set<String>): String {
        val ids = installed.ifEmpty { setOf(Region.SEED_REGION_ID) }
        val labels = ids.mapNotNull { id -> regionCatalog.firstOrNull { it.id == id }?.label }
            .ifEmpty { listOf("Germany") }
        return when {
            labels.size <= 2 -> labels.joinToString(", ")
            else -> "${labels.first()} +${labels.size - 1}"
        }
    }

    /** Fetch the region manifest for download sizes; updates [regionManifest]/[downloadState]. */
    private fun loadManifest() {
        downloadState.value = RegionDownloadState.LoadingManifest
        lifecycleScope.launch {
            val manifest = withKarooConnection(applicationContext) { system ->
                RegionCatalogClient(system).fetchManifest()
            }
            if (manifest == null) {
                downloadState.value = RegionDownloadState.ManifestFailed
            } else {
                regionManifest.value = manifest.regions.associateBy { it.id }
                downloadState.value = RegionDownloadState.Idle
                // Stash the full manifest (baseUrl) for the download step.
                lastManifest = manifest
            }
        }
    }

    private var lastManifest: io.resupply.karoo.data.RegionManifest? = null

    /** Download + install [region], driving [downloadState] through progress → done/failed. */
    private fun downloadRegion(region: Region) {
        val manifest = lastManifest ?: return
        val entry = manifest.regions.firstOrNull { it.id == region.id } ?: return
        downloadState.value = RegionDownloadState.Downloading(region.id, 0f)
        lifecycleScope.launch {
            val result = withKarooConnection(applicationContext) { system ->
                RegionCatalogClient(system).downloadAndInstall(
                    context = applicationContext,
                    manifest = manifest,
                    entry = entry,
                    scratchDir = cacheDir,
                    onProgress = { p ->
                        // Full progress covers the download; install is the short tail.
                        downloadState.value = if (p.done >= p.total && p.total > 0) {
                            RegionDownloadState.Installing(region.id)
                        } else {
                            RegionDownloadState.Downloading(region.id, p.fraction)
                        }
                    },
                )
            }
            downloadState.value = when (result) {
                is RegionCatalogClient.Result.Installed -> {
                    // Additive: record the region alongside any already installed. If this
                    // is the first explicit download on a seed-only install, also record the
                    // seed so removing this region doesn't hide the still-present Germany.
                    if (configStore.installedRegions.first().isEmpty()) {
                        configStore.addInstalledRegion(Region.SEED_REGION_ID)
                    }
                    configStore.addInstalledRegion(region.id)
                    RegionDownloadState.Done(region.id, result.poiCount)
                }
                is RegionCatalogClient.Result.SchemaMismatch ->
                    RegionDownloadState.Failed(region.id, "Update the app to download regions")
                is RegionCatalogClient.Result.Failed ->
                    RegionDownloadState.Failed(region.id, result.reason)
                null -> RegionDownloadState.Failed(region.id, "No connection")
            }
        }
    }

    /** Remove an installed region's POIs, then drop it from the installed set. */
    private fun removeRegion(region: Region) {
        downloadState.value = RegionDownloadState.Installing(region.id)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                PoiDatabase.removeRegion(applicationContext, region.id)
            }
            configStore.removeInstalledRegion(region.id)
            downloadState.value = RegionDownloadState.Idle
        }
    }

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
