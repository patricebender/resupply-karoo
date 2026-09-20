package io.resupply.karoo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
        ResupplyConfig(
            detourMeters = detour,
            smartDistance = smartDistance,
            enabledCategories = categories,
            safeWaterOnly = safeWaterOnly,
            favoritePoiIds = prefs[FAVORITE_POIS_KEY] ?: emptySet(),
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

    /**
     * Star/unstar a POI on the current roadbook. Favorites are id-keyed and persisted so
     * they survive a mid-ride restart, but are wiped by [clearFavorites] on a new build —
     * "one build" scope. See [ResupplyConfig.favoritePoiIds].
     */
    suspend fun setFavorite(poiId: String, favorite: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[FAVORITE_POIS_KEY] ?: emptySet()
            prefs[FAVORITE_POIS_KEY] = if (favorite) current + poiId else current - poiId
        }
    }

    /** Toggle the "show favorites only" filter (the header star). */
    suspend fun setFavoritesOnly(enabled: Boolean) {
        context.dataStore.edit { it[FAVORITES_ONLY_KEY] = enabled }
    }

    /**
     * Drop all favorites and the favorites-only filter. Called when a build replaces the POI
     * set (see [BuildController]/Settings clear), so favorites never leak across roadbooks.
     */
    suspend fun clearFavorites() {
        context.dataStore.edit { prefs ->
            prefs.remove(FAVORITE_POIS_KEY)
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

    private companion object {
        val DETOUR_KEY = intPreferencesKey("detour_meters")
        val CATEGORIES_KEY = stringSetPreferencesKey("enabled_categories")
        val SAFE_WATER_KEY = booleanPreferencesKey("safe_water_only")
        val SMART_DISTANCE_KEY = booleanPreferencesKey("smart_distance")
        val REGIONS_KEY = stringSetPreferencesKey("installed_regions")
        val FAVORITE_POIS_KEY = stringSetPreferencesKey("favorite_poi_ids")
        val FAVORITES_ONLY_KEY = booleanPreferencesKey("favorites_only")
    }
}
