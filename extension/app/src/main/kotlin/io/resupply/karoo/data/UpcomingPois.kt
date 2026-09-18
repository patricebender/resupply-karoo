package io.resupply.karoo.data

/**
 * Pure selection + formatting for the "Upcoming POIs" data field. No Android
 * dependencies so it can be unit-tested on the JVM.
 *
 * The field answers, per enabled category: what are the next POIs ahead on the
 * route and how far. "Ahead on route" is the POI's distance-along-route minus the
 * rider's current progress; POIs already passed drop out.
 */

/** One upcoming POI, resolved against the rider's current route progress. */
data class UpcomingPoi(
    val name: String?,
    val category: Category,
    /** Distance still to ride along the route to reach it, in meters. */
    val aheadMeters: Double,
    /** Cross-track detour off the route line, in meters (0 when unknown). */
    val detourMeters: Int,
)

/**
 * Group the next [perCat] POIs per enabled category, ordered by how far away they are.
 * The distance metric is pluggable via [distanceOf]: the route fields pass distance *ahead
 * on the route* ([aheadMetersFor] against the rider's progress); the nearby fields pass
 * straight-line distance from the rider. A POI whose [distanceOf] is null is dropped (behind
 * the rider on a route, or no location fix yet for nearby).
 *
 * One function, two metrics — so the field and the overview can't disagree on which POIs
 * count or how far they are (the reason [aheadMetersFor] is shared, below).
 *
 * @param distanceOf meters to reach a POI, or null to drop it.
 */
fun upcomingByCategory(
    pois: List<Poi>,
    enabledCategories: Set<Category>,
    perCat: Int = 3,
    // Narrow water to safe sources (see [ResupplyConfig.isSafeWaterSource]); only bites for
    // the water category. Kept parallel to the map/overview so the field agrees with them.
    safeWaterOnly: Boolean = true,
    distanceOf: (Poi) -> Double?,
): Map<Category, List<UpcomingPoi>> {
    if (enabledCategories.isEmpty()) return emptyMap()

    val byCat = LinkedHashMap<Category, MutableList<UpcomingPoi>>()
    for (cat in enabledCategories) byCat[cat] = mutableListOf()

    for (poi in pois) {
        val cat = Category.ofType(poi.type) ?: continue
        val bucket = byCat[cat] ?: continue
        if (cat == Category.WATER && safeWaterOnly && !ResupplyConfig.isSafeWaterSource(poi.tags)) continue
        val dist = distanceOf(poi) ?: continue
        bucket.add(UpcomingPoi(poi.name, cat, dist, poi.detourMeters))
    }

    return byCat.mapValues { (_, list) ->
        list.sortedBy { it.aheadMeters }.take(perCat)
    }
}

/**
 * How far ahead a POI is for a rider at [progressMeters], or null if it's behind (more
 * than [toleranceMeters] past). A POI can touch the route more than once
 * ([Poi.distancesAlongRoute]); we take its nearest crossing that is still ahead. Shared
 * by the Upcoming POIs field and the overview list so the two never disagree on which
 * POIs are ahead or by how much.
 */
fun aheadMetersFor(
    poi: Poi,
    progressMeters: Double,
    toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
): Double? =
    poi.distancesAlongRoute
        .map { it - progressMeters }
        .filter { it >= -toleranceMeters }
        .minOrNull()

/**
 * How far *behind* the rider a passed POI is, in meters (always ≥ 0), or null if it's
 * still ahead. The mirror of [aheadMetersFor]: for a POI with no crossing ahead, the
 * nearest crossing is behind, and this is how far back it is — so the list can say
 * "2.1km back" rather than a bare "passed" for a rider who might double back.
 */
fun behindMetersFor(poi: Poi, progressMeters: Double): Double? {
    if (aheadMetersFor(poi, progressMeters) != null) return null
    return poi.distancesAlongRoute
        .map { progressMeters - it } // positive = behind
        .filter { it > 0.0 }
        .minOrNull()
}

/**
 * Distance shown to the rider. Under 10 km keeps one decimal (`5.2km`); at or above,
 * rounds to a whole km (`12km`) — decimals are noise at that range on a small screen.
 */
fun formatKm(meters: Double): String {
    val km = meters.coerceAtLeast(0.0) / 1000.0
    return if (km < 10.0) "${(km * 10).toInt() / 10.0}km" else "${km.toInt()}km"
}

/** Detour suffix for the nearest POI in a row, e.g. `·+200m`. Empty when negligible. */
fun formatDetour(detourMeters: Int): String =
    if (detourMeters <= 0) "" else "·+${detourMeters}m"

/** Truncate a POI name so it can't push the distance columns out of alignment. */
fun elideName(name: String?, maxChars: Int): String? {
    if (name == null) return null
    return if (name.length <= maxChars) name else name.take(maxChars - 1).trimEnd() + "…"
}

/** Absorbs GPS jitter around the rider's position (meters). */
const val DEFAULT_TOLERANCE_METERS = 50.0
