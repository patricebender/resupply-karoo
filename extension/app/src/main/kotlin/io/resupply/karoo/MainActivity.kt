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
import io.resupply.karoo.data.WikipediaClient
import io.resupply.karoo.ui.SettingsScreen
import io.resupply.karoo.ui.PoiDetailScreen
import io.resupply.karoo.ui.RegionDownloadState
import io.resupply.karoo.ui.RegionsScreen
import io.resupply.karoo.ui.WaybookScreen
import io.resupply.karoo.ui.field.ACTION_BUILD
import io.resupply.karoo.ui.field.EXTRA_ACTION
import io.resupply.karoo.ui.hoursFor
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
    private lateinit var query: Deferred<PoiQuery>

    // Live route progress for the overview: a long-lived connection streaming
    // DISTANCE_TO_DESTINATION so the Waybook timeline can show where the rider is and
    // the list can show how far each POI is ahead. Best-effort — null distance means
    // "no live position" and the screen renders as it did before.
    private val progressSystem by lazy { KarooSystemService(applicationContext) }
    private val toDestMeters = MutableStateFlow<Double?>(null)
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
        }

        // Launched from the "Tap to build" data field: kick off a build immediately and
        // land on the Settings screen so the rider sees status (and can tweak categories).
        val buildOnLaunch =
            intent?.getStringExtra(EXTRA_ACTION) == ACTION_BUILD
        if (buildOnLaunch) runBuild()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ResupplyApp(initialScreen = if (buildOnLaunch) Screen.Settings else Screen.Waybook)
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
        if (intent.getStringExtra(EXTRA_ACTION) == ACTION_BUILD) runBuild()
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
        // Live position along the route, mirroring the field's math
        // (UpcomingPoisDataType): route length − distance-to-destination. Null when we
        // have no route or no live stream — the overview then drops the position cues.
        val progressMeters: Double? = toDest?.takeIf { routeLength > 0.0 }
            ?.let { (routeLength - it).coerceIn(0.0, routeLength) }

        var screen: Screen by remember { mutableStateOf(initialScreen) }
        // Hoisted here so the list scroll position is preserved across navigation to
        // the detail/filter screens and back.
        val waybookListState = rememberLazyListState()
        // Hoisted too: the Waybook screen leaves the composition when a detail/filter is
        // open, so a guard living there would reset and re-fire the initial auto-scroll on
        // every return, yanking the user off the row they drilled into. Keeping it here
        // makes "scroll to first POI ahead" a once-per-session action.
        val didInitialScroll = remember { mutableStateOf(false) }

        // Each fresh field-tap (entryTick bump) re-arms the auto-scroll and returns to the
        // overview: the rider wants "what's next from here", not wherever they'd scrolled.
        // Skips the initial composition (tick 0) so first launch keeps initialScreen.
        val tick = entryTick.intValue
        LaunchedEffect(tick) {
            if (tick == 0) return@LaunchedEffect
            didInitialScroll.value = false
            screen = Screen.Waybook
        }

        when (val s = screen) {
            is Screen.Waybook -> WaybookScreen(
                pois = pois,
                routeLengthMeters = routeLength,
                progressMeters = progressMeters,
                buildState = buildState,
                onBuild = ::runBuild,
                onOpenSettings = { screen = Screen.Settings },
                onOpenPoi = { screen = Screen.Detail(it.id) },
                reentryKey = tick,
                // OSM hours, or a Google result already fetched this session → badge in list.
                hoursOf = { poi -> hoursFor(poi, repository.cachedHours(poi.id)?.hours) },
                listState = waybookListState,
                didInitialScroll = didInitialScroll,
            )

            is Screen.Settings -> {
                val installed by configStore.installedRegions
                    .collectAsStateWithLifecycle(initialValue = emptySet())
                SettingsScreen(
                    config = config,
                    buildState = buildState,
                    hasPins = pois.isNotEmpty(),
                    installedSummary = installedSummary(installed),
                    onDetourChange = { m -> lifecycleScope.launch { configStore.setDetour(m) } },
                    onCategoryToggle = { c, on ->
                        lifecycleScope.launch { configStore.setCategoryEnabled(c, on) }
                    },
                    onBuild = ::runBuild,
                    onClear = {
                        repository.clear()
                        repository.setBuildState(BuildState.Idle)
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
                        onBack = { screen = Screen.Waybook },
                    )
                }
            }
        }
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
                BuildController(system, configStore, repository, query.await()).runBuild()
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
        // Categories where opening hours matter enough to spend a Google lookup.
        val GOOGLE_HOURS_CATEGORIES = setOf(
            Category.SUPERMARKETS, Category.CAFE_BAR, Category.RESTAURANTS, Category.FUEL,
            Category.ICE_CREAM, Category.HOTELS, Category.BIKE,
        )
    }
}
