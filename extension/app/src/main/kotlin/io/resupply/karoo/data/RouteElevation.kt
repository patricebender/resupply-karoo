package io.resupply.karoo.data

/**
 * Pure elevation-along-route math for the "climb to a POI" cue. No Android dependencies so it
 * unit-tests on the JVM (same shape as [UpcomingPois]).
 *
 * The Karoo hands us the route's elevation as a profile of (distanceAlongRoute, elevation) samples
 * (the `routeElevationPolyline`, decoded in [io.resupply.karoo.util.decodeElevationProfile]). Both
 * axes are metres. That profile is indexed by along-route distance — the same axis POIs live on
 * ([Poi.distancesAlongRoute], the rider's `progressMeters`).
 *
 * The cue is **total ascent** to reach the POI: the sum of every uphill between the rider and the
 * POI's crossing, NOT the signed endpoint difference. Net gain lies on rolling terrain — a POI that
 * ends at the rider's current height can still sit behind 2 000 m of climbing. Total ascent is the
 * "how hard to get there" number, so that's what we show.
 *
 * Only the route line has elevation; the short cross-track detour off the route to the POI has no
 * on-device samples, so it's deliberately excluded — exactly as distance-ahead ignores the detour.
 */

/**
 * A route's elevation samples as two parallel arrays, ascending by [distances]. Decoded once per
 * build and cached (see the repository), so lookups are cheap binary searches, never per-frame work.
 * Empty ([isEmpty]) means the route had no elevation polyline (or it failed to decode) — the UI
 * then simply omits the climb cue rather than inventing a flat 0.
 */
class ElevationProfile(
    val distances: DoubleArray,
    val elevations: DoubleArray,
) {
    init {
        require(distances.size == elevations.size) { "distances/elevations length mismatch" }
    }

    val isEmpty: Boolean get() = distances.isEmpty()

    /**
     * Interpolated elevation (metres) at along-route [distanceMeters], or null when the profile is
     * empty. The profile is coarse (precision-1 samples), so we linearly interpolate between the two
     * bracketing samples rather than snap to the nearest — keeping the ascent honest between samples.
     * A distance before the first / past the last sample clamps to that end.
     */
    fun elevationAt(distanceMeters: Double): Double? {
        if (isEmpty) return null
        if (distanceMeters <= distances.first()) return elevations.first()
        if (distanceMeters >= distances.last()) return elevations.last()

        // Binary search for the insertion point, then interpolate within the bracketing segment.
        var lo = 0
        var hi = distances.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (distances[mid] <= distanceMeters) lo = mid else hi = mid
        }
        val d0 = distances[lo]; val d1 = distances[hi]
        val e0 = elevations[lo]; val e1 = elevations[hi]
        val span = d1 - d0
        if (span <= 0.0) return e0 // coincident samples — no interpolation to do
        val t = (distanceMeters - d0) / span
        return e0 + t * (e1 - e0)
    }

    companion object {
        /** The no-data profile: a nearby build, or a route with no elevation polyline. */
        val EMPTY = ElevationProfile(DoubleArray(0), DoubleArray(0))
    }
}

/**
 * Total ascent (metres, always ≥ 0) to ride from [fromMeters] to [toMeters] along the route: the
 * sum of every uphill between them, with descents ignored. Null when [profile] is empty.
 *
 * Accumulates the positive deltas between consecutive profile samples inside the span, using the
 * *interpolated* elevation at the two endpoints so a span that starts/ends mid-segment counts only
 * its share of that segment's climb. A backwards or zero-length span (to ≤ from) is 0.
 */
fun ascentTo(profile: ElevationProfile, fromMeters: Double, toMeters: Double): Double? {
    if (profile.isEmpty) return null
    if (toMeters <= fromMeters) return 0.0
    val d = profile.distances

    // Walk the elevation at: the start, every sample strictly inside (from, to), then the end. The
    // climb is the sum of positive step-to-step differences along that walk. Endpoints are
    // interpolated (elevationAt) so partial first/last segments aren't over- or under-counted.
    var prevElev = profile.elevationAt(fromMeters) ?: return null
    var ascent = 0.0
    for (i in d.indices) {
        if (d[i] <= fromMeters) continue
        if (d[i] >= toMeters) break
        val e = profile.elevations[i]
        val step = e - prevElev
        if (step > 0.0) ascent += step
        prevElev = e
    }
    val endElev = profile.elevationAt(toMeters) ?: return null
    val lastStep = endElev - prevElev
    if (lastStep > 0.0) ascent += lastStep
    return ascent
}

/**
 * Total ascent (≥ 0) to reach [poi] from a rider at [progressMeters], or null when there's nothing
 * to show: the POI is behind the rider (no crossing ahead), or [profile] is empty. Uses
 * [aheadMetersFor] to pick *which* crossing is "the POI" (the nearest still ahead) so the climb and
 * the distance-ahead can never disagree about the same POI on a route that doubles back on itself.
 */
fun ascentForPoi(poi: Poi, progressMeters: Double, profile: ElevationProfile): Double? {
    if (profile.isEmpty) return null
    val ahead = aheadMetersFor(poi, progressMeters) ?: return null
    // aheadMetersFor returns distance-still-to-ride; the crossing's absolute along-route position is
    // the rider's progress plus that. Clamp the floor at progress so a within-tolerance "just behind"
    // POI measures climb from the rider, not a hair behind.
    val poiAlong = (progressMeters + ahead).coerceAtLeast(progressMeters)
    return ascentTo(profile, progressMeters, poiAlong)
}

/**
 * Position-independent total ascent (≥ 0) from the route START to [poi]'s crossing — the fallback
 * when there's no live progress (no GPS fix, or the ride hasn't started), so we can still show a
 * climb without knowing where the rider is. Measured to the POI's first along-route crossing. Null
 * when [profile] is empty or the POI has no route position.
 */
fun ascentFromStartForPoi(poi: Poi, profile: ElevationProfile): Double? {
    if (profile.isEmpty) return null
    // firstOrNull (not min): pair with the same crossing the distance mark shows
    // (distancesAlongRoute.firstOrNull), so "on route" and "climb total" describe one crossing.
    val poiAlong = poi.distancesAlongRoute.firstOrNull() ?: return null
    return ascentTo(profile, 0.0, poiAlong)
}

/**
 * Total ascent for display, e.g. `120 m` / `2100 m` (metric) or `390 ft` (imperial). Unsigned —
 * it's always a climb (≥ 0). Rounds to whole units. Used by the detail-screen pill; the compact
 * [formatAscentCompact] is for the tight list rail.
 */
fun formatElevation(meters: Double, useImperial: Boolean = false): String {
    val value = if (useImperial) meters / METERS_PER_FOOT else meters
    val unit = if (useImperial) "ft" else "m"
    return "${Math.round(value.coerceAtLeast(0.0))} $unit"
}

/**
 * Compact total ascent for the list rail, where it shares one line with the detour, e.g. `120m`,
 * `2.1k` (metric kilo-metres of climb once past 1 000 m), `390ft`, `6.5kft` (imperial). No space,
 * kilo-suffix past four digits so a big alpine climb doesn't blow out the narrow rail. Unsigned.
 */
fun formatAscentCompact(meters: Double, useImperial: Boolean = false): String {
    val value = (if (useImperial) meters / METERS_PER_FOOT else meters).coerceAtLeast(0.0)
    val unit = if (useImperial) "ft" else "m"
    return if (value < 1_000.0) {
        "${Math.round(value)}$unit"
    } else {
        // One decimal in thousands, e.g. 2_140 m → "2.1k" + unit.
        "${(value / 100).toInt() / 10.0}k$unit"
    }
}
