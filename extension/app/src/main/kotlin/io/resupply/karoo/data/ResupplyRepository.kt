package io.resupply.karoo.data

import android.content.Context
import io.resupply.karoo.build.BuildState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import timber.log.Timber

/**
 * App-scoped holder for the currently built POIs. Exposes a [StateFlow] the map
 * layer observes, and persists to a JSON file so POIs survive a process restart
 * and are available offline mid-ride.
 */
class ResupplyRepository private constructor(private val cacheFile: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val poiListSerializer = ListSerializer(Poi.serializer())

    private val _pois = MutableStateFlow<List<Poi>>(emptyList())
    val pois: StateFlow<List<Poi>> = _pois.asStateFlow()

    private val _buildState = MutableStateFlow<BuildState>(BuildState.Idle)
    val buildState: StateFlow<BuildState> = _buildState.asStateFlow()

    /**
     * Total length of the route the current POIs were built against, in meters.
     * 0 when the last build was /nearby (no route) — the strip hides itself then.
     * Positions POI dots on the route strip. Not persisted directly, but restored on
     * startup from the cached route POIs' along-route positions (see init) so a restored
     * roadbook reads as ROUTE, not NEARBY, before the ride re-emits nav state.
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

    // Sibling file for the persisted POI id → Google Place ID map.
    private val placeIdFile = File(cacheFile.parentFile, "resupply_place_ids.json")
    private val placeIdSerializer = MapSerializer(String.serializer(), String.serializer())

    init {
        // Restore previously built POIs so a route roadbook survives a mid-ride restart and is
        // on the map offline immediately. But ONLY a route set — a route POI carries
        // along-route positions ([Poi.distancesAlongRoute]); a nearby set has none. A nearby
        // set is only meaningful near where/when it was fetched, so after a restart (likely
        // elsewhere, later) it's stale — we drop it and let the background refresher repopulate
        // from the current location, rather than showing a stale POI with no "Live" context.
        runCatching {
            if (cacheFile.exists()) {
                val restored = json.decodeFromString(poiListSerializer, cacheFile.readText())
                val isRouteSet = restored.any { it.distancesAlongRoute.isNotEmpty() }
                if (isRouteSet) {
                    _pois.value = restored
                    // Restore a positive route length too, so the set reads as ROUTE (not NEARBY)
                    // before the ride re-emits nav state. Without this, a restart with no GPS fix
                    // yet flips poiSourceFor to NEARBY, and the nearby path drops every POI for
                    // lack of a rider location — surfacing a bogus "No favorites yet" / empty
                    // field on a route that's loaded but not started. The POIs' furthest
                    // along-route position is a safe lower bound; the next build/nav refines it.
                    _routeLengthMeters.value =
                        restored.flatMap { it.distancesAlongRoute }.maxOrNull() ?: 0.0
                    Timber.d("restored ${restored.size} cached route POIs")
                } else {
                    // Nearby (or empty) set → start clean; overwrite the stale cache file.
                    Timber.d("skipping ${restored.size} stale nearby POIs on startup")
                    if (restored.isNotEmpty()) persist()
                }
            }
        }.onFailure { Timber.w(it, "failed to load POI cache") }

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

    /** Replace the current POIs and persist them for offline use. */
    fun setPois(pois: List<Poi>) {
        _pois.value = pois
        persist()
    }

    /** Clear POIs at the start of a new build. */
    fun clear() {
        _pois.value = emptyList()
        _nearbyLive.value = false
        persist()
    }

    /** Append a page of POIs (dedup by id) and persist. */
    fun appendPois(page: List<Poi>) {
        val existing = _pois.value.associateBy { it.id }.toMutableMap()
        for (p in page) existing[p.id] = p
        _pois.value = existing.values.toList()
        persist()
    }

    private fun persist() {
        runCatching { cacheFile.writeText(json.encodeToString(poiListSerializer, _pois.value)) }
            .onFailure { Timber.w(it, "failed to persist POI cache") }
    }

    companion object {
        // Performance-cache window for fetched Google hours (ToS: short-term only).
        private const val HOURS_TTL_MS = 12 * 60 * 60 * 1000L

        @Volatile
        private var instance: ResupplyRepository? = null

        fun get(context: Context): ResupplyRepository =
            instance ?: synchronized(this) {
                instance ?: ResupplyRepository(
                    File(context.applicationContext.filesDir, "resupply_pois.json"),
                ).also { instance = it }
            }
    }
}
