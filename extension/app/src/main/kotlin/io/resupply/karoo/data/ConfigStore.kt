package io.resupply.karoo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "resupply_config")

/**
 * Reads/writes [ResupplyConfig] via DataStore. Both the app UI and the build
 * flow use this so config is a single source of truth.
 */
class ConfigStore(private val context: Context) {

    val config: Flow<ResupplyConfig> = context.dataStore.data.map { prefs ->
        val detour = prefs[DETOUR_KEY] ?: ResupplyConfig.DEFAULT_DETOUR_METERS
        val enabledIds = prefs[CATEGORIES_KEY]
        val categories = if (enabledIds == null) {
            Category.entries.toSet()
        } else {
            enabledIds.mapNotNull { id -> Category.entries.find { it.id == id } }.toSet()
        }
        // Absent key reads as on, so existing installs get the safe-water default without a
        // first-run write and without a surprise flip.
        val safeWaterOnly = prefs[SAFE_WATER_KEY] ?: true
        // Absent key reads as on, so existing installs get smart distance without a first-run
        // write; detourMeters stays persisted for when the rider turns it off.
        val smartDistance = prefs[SMART_DISTANCE_KEY] ?: true
        // Live favorites are DERIVED, not stored separately: they're the cache entry for the
        // route currently loaded ([CURRENT_ROUTE_KEY]). One source of truth — the live set and
        // the per-route cache can't desync, and loading a route needs no restore step. Route-less
        // (nearby) has no current route key, so favorites are naturally empty.
        val routeKey = prefs[CURRENT_ROUTE_KEY]
        val favoriteIds = routeKey
            ?.let { key -> decodeRouteFavorites(prefs[FAVORITES_BY_ROUTE_KEY]).firstOrNull { it.key == key }?.ids?.toSet() }
            ?: emptySet()
        ResupplyConfig(
            detourMeters = detour,
            smartDistance = smartDistance,
            enabledCategories = categories,
            safeWaterOnly = safeWaterOnly,
            favoritePoiIds = favoriteIds,
            favoritesOnly = prefs[FAVORITES_ONLY_KEY] ?: false,
        )
    }

    suspend fun setDetour(meters: Int) {
        context.dataStore.edit { it[DETOUR_KEY] = meters }
    }

    suspend fun setSafeWaterOnly(enabled: Boolean) {
        context.dataStore.edit { it[SAFE_WATER_KEY] = enabled }
    }

    suspend fun setSmartDistance(enabled: Boolean) {
        context.dataStore.edit { it[SMART_DISTANCE_KEY] = enabled }
    }

    suspend fun setCategoryEnabled(category: Category, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[CATEGORIES_KEY]?.toMutableSet()
                ?: Category.entries.map { it.id }.toMutableSet()
            if (enabled) current.add(category.id) else current.remove(category.id)
            prefs[CATEGORIES_KEY] = current
        }
    }

    // --- Favorites -----------------------------------------------------------------------------
    //
    // Favorites are stored per route in [FAVORITES_BY_ROUTE_KEY], keyed by the route's polyline
    // hash. The "live" set the UI reads ([ResupplyConfig.favoritePoiIds]) is DERIVED in the config
    // flow as the entry for [CURRENT_ROUTE_KEY] — there is no separate live copy to keep in sync.
    // A route survives rebuilds and restarts: loading the same route again shows the same stars.
    // Visibility under the current settings is still decided at render time by
    // [ResupplyConfig.showsPoi] (categories/safe-water), so a favorite whose category is off simply
    // isn't shown and returns when re-enabled — nothing is intersected or pruned here.

    /**
     * Star/unstar a POI on the current roadbook. Writes straight into the current route's cache
     * entry (the single source of truth), so the derived live set updates and the stars persist
     * across rebuilds/restarts of that route. A no-op with no route loaded ([CURRENT_ROUTE_KEY]
     * unset) — there's nothing to favorite against.
     */
    suspend fun setFavorite(poiId: String, favorite: Boolean) {
        context.dataStore.edit { prefs ->
            val routeKey = prefs[CURRENT_ROUTE_KEY] ?: return@edit
            val entries = decodeRouteFavorites(prefs[FAVORITES_BY_ROUTE_KEY]).toMutableList()
            val existing = entries.firstOrNull { it.key == routeKey }?.ids?.toMutableSet() ?: mutableSetOf()
            if (favorite) existing.add(poiId) else existing.remove(poiId)
            entries.removeAll { it.key == routeKey }
            if (existing.isNotEmpty()) {
                entries += RouteFavorites(routeKey, existing.toList(), System.currentTimeMillis())
                if (entries.size > MAX_CACHED_ROUTES) {
                    entries.sortBy { it.atMs } // oldest first
                    repeat(entries.size - MAX_CACHED_ROUTES) { entries.removeAt(0) }
                }
            }
            prefs[FAVORITES_BY_ROUTE_KEY] = encodeRouteFavorites(entries)
        }
    }

    /** Toggle the "show favorites only" filter (the header star). A global view filter, not per-route. */
    suspend fun setFavoritesOnly(enabled: Boolean) {
        context.dataStore.edit { it[FAVORITES_ONLY_KEY] = enabled }
    }

    /** Forget a route's cached favorites (the Settings trashcan). */
    suspend fun forgetRouteFavorites(routeKey: String) {
        context.dataStore.edit { prefs ->
            val entries = decodeRouteFavorites(prefs[FAVORITES_BY_ROUTE_KEY])
                .filterNot { it.key == routeKey }
            prefs[FAVORITES_BY_ROUTE_KEY] = encodeRouteFavorites(entries)
        }
    }

    /**
     * The route key of the roadbook currently loaded, or null when route-less. The derived live
     * favorites are this route's cache entry; loading a route sets this and the stars follow.
     */
    suspend fun currentRouteKey(): String? =
        context.dataStore.data.first()[CURRENT_ROUTE_KEY]

    /**
     * Point the derived favorites at [routeKey]'s entry. Called when a route's roadbook becomes
     * current (build or cache realization). Also clears the favorites-only filter on a genuine
     * route *change* so the header star doesn't silently persist across routes.
     */
    suspend fun setCurrentRouteKey(routeKey: String) {
        context.dataStore.edit { prefs ->
            if (prefs[CURRENT_ROUTE_KEY] != routeKey) prefs.remove(FAVORITES_ONLY_KEY)
            prefs[CURRENT_ROUTE_KEY] = routeKey
        }
    }

    /** Route-less (nearby): no current route → derived favorites are empty; drop the view filter too. */
    suspend fun clearCurrentRouteKey() {
        context.dataStore.edit { prefs ->
            prefs.remove(CURRENT_ROUTE_KEY)
            prefs.remove(FAVORITES_ONLY_KEY)
        }
    }

    /**
     * The set of installed region ids. Installs are additive, so this grows as the rider
     * downloads regions and shrinks on removal. Empty means the DB is still the untouched
     * bundled seed (Germany); the picker treats empty as `{germany}` so the seed shows as
     * installed without a first-run write (see [Region.SEED_REGION_ID]).
     */
    val installedRegions: Flow<Set<String>> =
        context.dataStore.data.map { it[REGIONS_KEY] ?: emptySet() }

    suspend fun addInstalledRegion(regionId: String) {
        context.dataStore.edit { prefs ->
            prefs[REGIONS_KEY] = (prefs[REGIONS_KEY] ?: emptySet()) + regionId
        }
    }

    suspend fun removeInstalledRegion(regionId: String) {
        context.dataStore.edit { prefs ->
            prefs[REGIONS_KEY] = (prefs[REGIONS_KEY] ?: emptySet()) - regionId
        }
    }

    /** One route's cached favorites: the route key, its starred POI ids, and last-touch time (LRU). */
    @Serializable
    private data class RouteFavorites(val key: String, val ids: List<String>, val atMs: Long)

    private fun decodeRouteFavorites(raw: String?): List<RouteFavorites> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString(routeFavoritesSerializer, raw) }
            .onFailure { Timber.w(it, "failed to decode route favorites cache") }
            .getOrDefault(emptyList())
    }

    private fun encodeRouteFavorites(entries: List<RouteFavorites>): String =
        json.encodeToString(routeFavoritesSerializer, entries)

    private companion object {
        val DETOUR_KEY = intPreferencesKey("detour_meters")
        val CATEGORIES_KEY = stringSetPreferencesKey("enabled_categories")
        val SAFE_WATER_KEY = booleanPreferencesKey("safe_water_only")
        val SMART_DISTANCE_KEY = booleanPreferencesKey("smart_distance")
        val REGIONS_KEY = stringSetPreferencesKey("installed_regions")
        val FAVORITES_ONLY_KEY = booleanPreferencesKey("favorites_only")
        val FAVORITES_BY_ROUTE_KEY = stringPreferencesKey("favorites_by_route")
        val CURRENT_ROUTE_KEY = stringPreferencesKey("current_route_key")

        private val json = Json { ignoreUnknownKeys = true }
        private val routeFavoritesSerializer = ListSerializer(RouteFavorites.serializer())
    }
}
