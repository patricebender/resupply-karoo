package io.resupply.karoo.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import timber.log.Timber

/**
 * How many routes' worth of history the per-route caches keep (LRU). Shared by [RoadbookCache]
 * (built POI sets) and the favorites-by-route cache in [ConfigStore] so the two siblings — both
 * keyed on route identity — age out together rather than at different depths.
 */
const val MAX_CACHED_ROUTES = 10

/**
 * Persisted cache of built POI sets, keyed by a route build key (see
 * [io.resupply.karoo.build.routeBuildKey]). Independent of [ResupplyRepository], which holds the
 * one set currently *shown* on the map/fields: this store keeps the built sets so that
 *  - rebuilding a route with unchanged inputs skips the DB query, and
 *  - loading a route the rider has built before realizes its POIs instantly, with no tap
 *    (see the extension's nav watcher) — the roadbook is ready the moment the route loads.
 *
 * It survives [ResupplyRepository.clear] (which only empties the live set), so a cleared or
 * route-changed roadbook can still be re-promoted from here. Bounded to [MAX_CACHED_ROUTES]
 * most-recently-touched routes (LRU) so it can't grow unbounded across a season of rides.
 *
 * App-scoped singleton, like [ResupplyRepository], so the extension and the build controller
 * share one instance (and its in-memory map) with no disk round-trip on lookup.
 */
class RoadbookCache private constructor(private val cacheFile: File) {

    private val json = Json { ignoreUnknownKeys = true }

    /** One route's built set: the build key, its POIs, and last-touch time (LRU). */
    @Serializable
    private data class Entry(val key: String, val pois: List<Poi>, val atMs: Long)

    private val serializer = ListSerializer(Entry.serializer())

    // In-memory mirror of the persisted entries; the single source of truth at runtime.
    private val entries: MutableList<Entry> = mutableListOf()

    init {
        runCatching {
            if (cacheFile.exists()) {
                entries.addAll(json.decodeFromString(serializer, cacheFile.readText()))
                Timber.d("loaded ${entries.size} cached roadbooks")
            }
        }.onFailure { Timber.w(it, "failed to load roadbook cache") }
    }

    /** The cached POIs for [buildKey], or null if none. Does not touch LRU order (a plain read). */
    @Synchronized
    fun get(buildKey: String): List<Poi>? = entries.firstOrNull { it.key == buildKey }?.pois

    /** Cache [pois] under [buildKey] (upsert), refreshing its LRU position; evict oldest past cap. */
    @Synchronized
    fun put(buildKey: String, pois: List<Poi>) {
        entries.removeAll { it.key == buildKey }
        entries.add(Entry(buildKey, pois, System.currentTimeMillis()))
        if (entries.size > MAX_CACHED_ROUTES) {
            entries.sortBy { it.atMs } // oldest first
            while (entries.size > MAX_CACHED_ROUTES) entries.removeAt(0)
        }
        persist()
    }

    /**
     * Forget every cached variant of the route whose geometry hashes to [routeKey] — the Settings
     * trashcan's explicit reset. A build key is `<routeKey>:<detour>:<regions>:<dbVersion>` (see
     * [io.resupply.karoo.build.routeBuildKey]), so all variants of one route share the `routeKey:`
     * prefix; dropping them means the next build of that route re-queries rather than serving the
     * set the rider just cleared.
     */
    @Synchronized
    fun removeRoute(routeKey: String) {
        if (entries.removeAll { it.key.startsWith("$routeKey:") }) persist()
    }

    private fun persist() {
        runCatching { cacheFile.writeText(json.encodeToString(serializer, entries)) }
            .onFailure { Timber.w(it, "failed to persist roadbook cache") }
    }

    companion object {
        @Volatile
        private var instance: RoadbookCache? = null

        fun get(context: Context): RoadbookCache =
            instance ?: synchronized(this) {
                instance ?: RoadbookCache(
                    File(context.applicationContext.filesDir, "resupply_roadbook_cache.json"),
                ).also { instance = it }
            }
    }
}
