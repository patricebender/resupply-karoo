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
) {
    companion object {
        /**
         * Selectable detour radii. Irregular by design: tight 100/250 m options for
         * on-route resupply, then 500 m increments up to 5 km. The settings slider snaps
         * to these; nothing else assumes a uniform step.
         */
        val DETOUR_OPTIONS_METERS = listOf(100, 250, 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000)
        const val DEFAULT_DETOUR_METERS = 250
    }
}
