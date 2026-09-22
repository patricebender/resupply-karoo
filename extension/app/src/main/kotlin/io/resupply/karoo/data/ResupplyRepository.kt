package io.resupply.karoo.data

import android.content.Context
import io.resupply.karoo.build.BuildState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import timber.log.Timber

/**
 * App-scoped holder for the currently shown POIs — the live roadbook the map layer and data
 * fields observe. In-memory only: durability lives in [RoadbookCache] (built sets, keyed by
 * route) and is realized into here when a route loads, so this set starts empty each session and
 * is filled only in response to a present route (or the nearby refresher). See [setPois].
 *
 * (Google Place IDs are the one thing persisted here — a small sibling file, unrelated to the
 * roadbook — since Maps ToS permits keeping them indefinitely.)
 */
class ResupplyRepository private constructor(filesDir: File) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _pois = MutableStateFlow<List<Poi>>(emptyList())
    val pois: StateFlow<List<Poi>> = _pois.asStateFlow()

    private val _buildState = MutableStateFlow<BuildState>(BuildState.Idle)
    val buildState: StateFlow<BuildState> = _buildState.asStateFlow()

    /**
     * Total length of the route the current POIs were built against, in meters.
     * 0 when the last build was /nearby (no route) — the strip hides itself then.
     * Positions POI dots on the route strip. Set alongside the POIs whenever a roadbook becomes
     * current (build or cache realization); a nearby set zeroes it.
     */
    private val _routeLengthMeters = MutableStateFlow(0.0)
    val routeLengthMeters: StateFlow<Double> = _routeLengthMeters.asStateFlow()

    /**
     * Whether nearby POIs are being kept fresh automatically as the rider moves (route-less
     * background refresh). Drives the overview's steady "Live" badge. Set true by the
     * refresher once it's tracking, false when a route loads or the roadbook is cleared. Not
     * persisted — a fresh session starts not-live until the refresher begins.
     */
    private val _nearbyLive = MutableStateFlow(false)
    val nearbyLive: StateFlow<Boolean> = _nearbyLive.asStateFlow()

    /**
     * Whether the rider has paused route-less live search (the Settings toggle). While true the
     * background refresher/ticker stand down — no auto-search, no [nearbyLive] — so the overview
     * falls back to its "find live resupply" invitation, exactly as before a search started.
     * Session-scoped (not persisted) and auto-reset when a route loads or a fresh nearby build
     * runs: it's "paused for now", never a sticky global that silently suppresses live search.
     */
    private val _nearbyPaused = MutableStateFlow(false)
    val nearbyPaused: StateFlow<Boolean> = _nearbyPaused.asStateFlow()

    fun setNearbyPaused(paused: Boolean) {
        _nearbyPaused.value = paused
    }

    /**
     * Stop live search and return to the pre-search state: clear the shown POIs, drop the "Live"
     * status, and mark paused so the refresher stands down until the rider starts a new search
     * (the overview's Find button) or a route loads.
     */
    fun pauseNearby() {
        _nearbyPaused.value = true
        _nearbyLive.value = false
        _pois.value = emptyList()
        _buildState.value = BuildState.Idle
    }

    /** In-memory cache of fetched place descriptions, keyed by POI id. */
    private val descriptions = mutableMapOf<String, String>()

    /**
     * POI id → resolved Google Place ID. Persisted: Maps ToS permits caching Place IDs
     * indefinitely, so we skip the Text Search on repeat hours lookups. (Hours
     * themselves are never cached — those are fetched live each time.)
     */
    private val placeIdCache = mutableMapOf<String, String>()

    fun setBuildState(state: BuildState) {
        _buildState.value = state
    }

    fun setRouteLength(meters: Double) {
        _routeLengthMeters.value = meters
    }

    fun setNearbyLive(live: Boolean) {
        _nearbyLive.value = live
    }

    fun cachedDescription(poiId: String): String? = descriptions[poiId]

    fun cacheDescription(poiId: String, text: String) {
        descriptions[poiId] = text
    }

    /**
     * In-memory cache of fetched Google opening hours, keyed by POI id, with a short
     * TTL. Maps ToS permits temporary caching *solely for performance* (not a permanent
     * store) — a bike ride is well within that. NOT persisted; gone when the process
     * ends, and re-fetched once older than [HOURS_TTL_MS].
     */
    private data class CachedHours(val result: PlacesClient.Result, val atMs: Long)
    private val hoursCache = mutableMapOf<String, CachedHours>()

    fun cachedHours(poiId: String): PlacesClient.Result? =
        hoursCache[poiId]?.takeIf { System.currentTimeMillis() - it.atMs < HOURS_TTL_MS }?.result

    fun cacheHours(poiId: String, result: PlacesClient.Result) {
        hoursCache[poiId] = CachedHours(result, System.currentTimeMillis())
    }

    fun cachedPlaceId(poiId: String): String? = placeIdCache[poiId]

    fun cachePlaceId(poiId: String, placeId: String) {
        placeIdCache[poiId] = placeId
        persistPlaceIds()
    }

    // File for the persisted POI id → Google Place ID map.
    private val placeIdFile = File(filesDir, "resupply_place_ids.json")
    private val placeIdSerializer = MapSerializer(String.serializer(), String.serializer())

    init {
        // Load persisted Place IDs (allowed to keep indefinitely per Maps ToS).
        runCatching {
            if (placeIdFile.exists()) {
                placeIdCache.putAll(
                    json.decodeFromString(placeIdSerializer, placeIdFile.readText()),
                )
            }
        }.onFailure { Timber.w(it, "failed to load place-id cache") }
    }

    private fun persistPlaceIds() {
        runCatching { placeIdFile.writeText(json.encodeToString(placeIdSerializer, placeIdCache)) }
            .onFailure { Timber.w(it, "failed to persist place-id cache") }
    }

    /** Show [pois] as the current roadbook. The durable copy lives in [RoadbookCache], not here. */
    fun setPois(pois: List<Poi>) {
        _pois.value = pois
    }

    /** Clear the current roadbook (start of a new build, or route removed). */
    fun clear() {
        _pois.value = emptyList()
        _nearbyLive.value = false
    }

    companion object {
        // Performance-cache window for fetched Google hours (ToS: short-term only).
        private const val HOURS_TTL_MS = 12 * 60 * 60 * 1000L

        @Volatile
        private var instance: ResupplyRepository? = null

        fun get(context: Context): ResupplyRepository =
            instance ?: synchronized(this) {
                instance ?: ResupplyRepository(context.applicationContext.filesDir)
                    .also { instance = it }
            }
    }
}
