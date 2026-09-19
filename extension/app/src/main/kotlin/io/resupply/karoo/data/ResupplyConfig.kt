package io.resupply.karoo.data

/**
 * POI categories the rider can toggle. `id` matches the backend contract
 * (backend/src/contract.ts Category) and is what we send in the build request.
 */
enum class Category(val id: String, val label: String) {
    RESTAURANTS("restaurants", "Restaurants"),
    SUPERMARKETS("supermarkets", "Supermarkets"),
    CAFE_BAR("cafe_bar", "Café & Bar"),
    WATER("water", "Water"),
    TOILET("toilet", "Toilets"),
    BIKE("bike", "Bike shops"),
    FUEL("fuel", "Fuel stations"),
    ICE_CREAM("ice_cream", "Ice Cream"),
    HOTELS("hotels", "Hotels");

    companion object {
        /** Map a POI `type` (as stored in the DB) back to its category. */
        fun ofType(type: String): Category? = when (type) {
            "FOOD" -> RESTAURANTS
            "CONVENIENCE_STORE" -> SUPERMARKETS
            "COFFEE", "BAR" -> CAFE_BAR
            "REST_STOP" -> WATER
            "RESTROOM" -> TOILET
            "BIKE_SHOP" -> BIKE
            "GAS_STATION" -> FUEL
            "ICE_CREAM" -> ICE_CREAM
            "LODGING" -> HOTELS
            else -> null
        }
    }
}

/** Rider-configurable build settings. */
data class ResupplyConfig(
    /** Detour search radius around the route, in meters. */
    val detourMeters: Int = DEFAULT_DETOUR_METERS,
    val enabledCategories: Set<Category> = setOf(Category.WATER, Category.BIKE),
    /**
     * When true, water POIs are narrowed to *safe* sources ([isSafeWaterSource]) —
     * unnamed/untagged fountains, springs and wells (potability unknown) are hidden.
     * A render-time filter over the built set, like the category toggles; no rebuild.
     * Only bites while [Category.WATER] is enabled.
     */
    val safeWaterOnly: Boolean = true,
    /**
     * The rider's starred POIs on the current roadbook, by id. Persisted for one build (see
     * [ConfigStore.clearFavorites]); route-mode only. Drives the timeline stars and the
     * favorites data field; combined with [favoritesOnly] it also narrows [showsPoi].
     */
    val favoritePoiIds: Set<String> = emptySet(),
    /**
     * The "show favorites only" filter (the header star). When on, [showsPoi] hides every POI
     * that isn't a favorite — so the list, map pins and fields all narrow together.
     */
    val favoritesOnly: Boolean = false,
) {
    /**
     * Whether [poi] should be shown under this config: its category must be enabled, and —
     * for water, when [safeWaterOnly] — it must be a safe source, and — when [favoritesOnly] —
     * it must be a favorite. The single render-time gate shared by the map pins, the overview
     * list and the Upcoming POIs field, so they can't disagree on what's visible.
     */
    fun showsPoi(poi: Poi): Boolean {
        val cat = Category.ofType(poi.type) ?: return false
        if (cat !in enabledCategories) return false
        if (cat == Category.WATER && safeWaterOnly && !isSafeWaterSource(poi.tags)) return false
        if (favoritesOnly && poi.id !in favoritePoiIds) return false
        return true
    }

    /** Whether [poi] is starred — for the list/detail/timeline star state. */
    fun isFavorite(poi: Poi): Boolean = poi.id in favoritePoiIds

    companion object {
        /**
         * Selectable detour radii. Irregular by design: tight 100/250 m options for
         * on-route resupply, then 500 m increments up to 5 km. The settings slider snaps
         * to these; nothing else assumes a uniform step.
         */
        val DETOUR_OPTIONS_METERS = listOf(100, 250, 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000)
        const val DEFAULT_DETOUR_METERS = 250

        /**
         * A water POI is a *safe* source iff it's a tap (potable by definition), a graveyard
         * (heuristic: a tap is nearly always present), or explicitly tagged drinking water.
         * Reads the pipeline's synthetic `water_subtype`/`drinking_water` tags (load-into-sqlite.ts);
         * everything else — an unnamed fountain/spring/well with unknown potability — is not safe.
         */
        fun isSafeWaterSource(tags: Map<String, String>): Boolean =
            tags["water_subtype"] == "tap" ||
                tags["water_subtype"] == "graveyard" ||
                tags["drinking_water"] == "yes"
    }
}
