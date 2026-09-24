package io.resupply.karoo.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

/** Meters per degree of latitude (constant); also the equatorial meters-per-degree of longitude. */
const val METERS_PER_DEG_LAT = 111_320.0

/** Decode a polyline directly into [LatLng] points. */
fun decodeLatLng(encoded: String, precision: Int = 5): List<LatLng> =
    decodePolyline(encoded, precision).map { LatLng(it.first, it.second) }

/** Great-circle distance in meters. Mirrors backend polyline.ts. */
fun haversine(a: LatLng, b: LatLng): Double {
    val R = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * R * asin(sqrt(h))
}

/** Cumulative distance (meters) at each route vertex. */
fun cumulativeDistances(route: List<LatLng>): DoubleArray {
    val out = DoubleArray(route.size)
    for (i in 1 until route.size) out[i] = out[i - 1] + haversine(route[i - 1], route[i])
    return out
}

/**
 * A point projected onto the route: the cross-track [distance] (meters) to the nearest
 * segment, the cumulative route distance [along] at the closest point, and which [side]
 * of the route the point lies on relative to travel direction — `+1` left, `-1` right,
 * `0` exactly on the line. [side] is the sign of the 2D cross product of the winning
 * segment's direction and the point-relative vector; the UI decides how that maps to
 * screen up/down.
 */
data class RouteProjection(val distance: Double, val along: Double, val side: Int)

/**
 * Project point [p] onto the route polyline against the nearest *segment*, using a
 * local equirectangular projection around [p]. Mirrors backend distanceToRoute (which
 * only needs distance+along); [RouteProjection.side] is the on-device addition.
 *
 * Linear in route length — fine for a one-off, but O(candidates × segments) when called per
 * candidate. For that, build a [RouteIndex] once and use the indexed overload below, which
 * gives the *same* result over far fewer segments.
 */
fun distanceToRoute(
    route: List<LatLng>,
    cumulative: DoubleArray,
    p: LatLng,
): RouteProjection {
    // Every segment 1..size-1, in order. One-off call (no index), so building the index array
    // here is fine — the hot per-candidate path goes through RouteIndex instead.
    val all = IntArray(route.size - 1) { it + 1 }
    return projectOntoSegments(route, cumulative, p, all, all.size)
}

/** Shared projection kernel: exact nearest-segment projection of [p] over the [count] segment
 *  indices in [segs] (each is a segment from vertex `i-1` to `i`). Takes a primitive `IntArray`
 *  (not a boxed `Iterable`) because this is the per-candidate hot loop — the caller reuses one
 *  buffer across candidates, so no allocation or Integer boxing happens here. */
private fun projectOntoSegments(
    route: List<LatLng>,
    cumulative: DoubleArray,
    p: LatLng,
    segs: IntArray,
    count: Int,
): RouteProjection {
    val mPerDegLat = METERS_PER_DEG_LAT
    val mPerDegLng = METERS_PER_DEG_LAT * cos(Math.toRadians(p.lat))
    val px = p.lng * mPerDegLng
    val py = p.lat * mPerDegLat

    var best = Double.POSITIVE_INFINITY
    var bestAlong = 0.0
    var bestSide = 0
    for (k in 0 until count) {
        val i = segs[k]
        val a = route[i - 1]
        val b = route[i]
        val ax = a.lng * mPerDegLng; val ay = a.lat * mPerDegLat
        val bx = b.lng * mPerDegLng; val by = b.lat * mPerDegLat
        val dx = bx - ax; val dy = by - ay
        val segLen2 = dx * dx + dy * dy
        val t = if (segLen2 == 0.0) 0.0
        else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / segLen2))
        val cx = ax + t * dx; val cy = ay + t * dy
        val d = hypot(px - cx, py - cy)
        if (d < best) {
            best = d
            bestAlong = cumulative[i - 1] + hypot(cx - ax, cy - ay)
            // Cross product (segment dir) × (a→p): +ve is left of travel direction.
            val cross = dx * (py - ay) - dy * (px - ax)
            bestSide = if (cross > 0) 1 else if (cross < 0) -1 else 0
        }
    }
    return RouteProjection(best, bestAlong, bestSide)
}

/**
 * A coarse spatial grid over a route's segments so [distanceToRoute] can test only the segments
 * near a query point instead of all of them — turning per-candidate projection from
 * O(segments) into O(few). Exact, not approximate: with a cell size ≥ [reachMeters] (the max
 * cross-track distance the caller cares about), every segment that could be the true nearest of
 * any point is within the ±[SEARCH_CELLS] window searched by [project], so the indexed
 * projection returns the identical [RouteProjection] to the linear scan.
 *
 * Cells are in raw lat/lng, sized in degrees from [reachMeters] at a reference latitude (the
 * route mid-lat) for the longitude scale. Each segment is registered in *every* cell its bbox
 * overlaps, so a segment longer than a cell is still found from all its cells.
 */
class RouteIndex(
    private val route: List<LatLng>,
    private val cumulative: DoubleArray,
    reachMeters: Double,
) {
    private val cellLat: Double
    private val cellLng: Double
    private val cells: HashMap<Long, MutableList<Int>> = HashMap()

    init {
        val midLat = if (route.isEmpty()) 0.0 else (route.first().lat + route.last().lat) / 2
        val mPerDegLng = METERS_PER_DEG_LAT * cos(Math.toRadians(midLat))
        // Guard tiny/degenerate scales so a cell is always a positive span.
        cellLat = max(reachMeters / METERS_PER_DEG_LAT, 1e-6)
        cellLng = max(reachMeters / max(mPerDegLng, 1.0), 1e-6)
        for (i in 1 until route.size) {
            val a = route[i - 1]; val b = route[i]
            val minLatI = floorCell(min(a.lat, b.lat), cellLat)
            val maxLatI = floorCell(max(a.lat, b.lat), cellLat)
            val minLngI = floorCell(min(a.lng, b.lng), cellLng)
            val maxLngI = floorCell(max(a.lng, b.lng), cellLng)
            for (li in minLatI..maxLatI) for (gi in minLngI..maxLngI) {
                cells.getOrPut(key(li, gi)) { ArrayList() }.add(i)
            }
        }
    }

    /**
     * Reusable per-thread scratch for [project]. Gathering a point's neighbourhood segments was the
     * hot loop's dominant cost when done with a `sortedSetOf<Int>()` per call — a red-black tree of
     * ~hundreds of *boxed* Integers, allocated and thrown away for every candidate. This holds a
     * generation-stamped visited array (dedup without clearing between calls: bump [gen], a segment
     * is "seen this call" iff `seen[i] == gen`) plus a flat [buf] to collect into. Sized to the
     * route once and reused across all candidates in a slice. NOT thread-safe — give each parallel
     * worker its own via [newScratch].
     */
    class Scratch(routeSize: Int) {
        val seen = IntArray(routeSize)      // 0 == "never seen"; gen starts at 1 so the init is valid
        val buf = IntArray(routeSize)
        var gen = 0
    }

    /** A [Scratch] sized for this index's route. One per thread. */
    fun newScratch() = Scratch(route.size)

    /** Same result as the linear [distanceToRoute], over only the segments near [p]. Allocates a
     *  one-shot [Scratch]; the per-candidate hot path should reuse one via [project] + [newScratch]. */
    fun project(p: LatLng): RouteProjection = project(p, newScratch())

    /**
     * Same result as the linear [distanceToRoute], over only the segments near [p], reusing [s] so
     * the neighbourhood gather is allocation-free. Fill [s.buf] with the deduped neighbourhood in
     * ascending order (order matters: ties — a point equidistant from two segments — must break to
     * the same winner as the linear scan, else `along`/`side` could differ on near-ties).
     */
    fun project(p: LatLng, s: Scratch): RouteProjection {
        val count = gatherNear(p, s)
        if (count == 0) return RouteProjection(Double.POSITIVE_INFINITY, 0.0, 0)
        return projectOntoSegments(route, cumulative, p, s.buf, count)
    }

    /** Fill [s.buf] with the deduped segment indices near [p], ascending, and return the count.
     *  Dedup uses the generation stamp; sort once at the end for the tie-break invariant. */
    private fun gatherNear(p: LatLng, s: Scratch): Int {
        val li = floorCell(p.lat, cellLat)
        val gi = floorCell(p.lng, cellLng)
        val gen = ++s.gen
        var n = 0
        for (dl in -SEARCH_CELLS..SEARCH_CELLS) for (dg in -SEARCH_CELLS..SEARCH_CELLS) {
            val list = cells[key(li + dl, gi + dg)] ?: continue
            for (i in list) {
                if (s.seen[i] != gen) { s.seen[i] = gen; s.buf[n++] = i }
            }
        }
        if (n > 1) java.util.Arrays.sort(s.buf, 0, n)
        return n
    }

    /**
     * The number of route segments the index would test for [p] — the size of its neighbourhood
     * window. Exposed so a regression test can assert per-candidate cost stays O(nearby), not
     * O(all segments): a linear-scan reintroduction blows this up to ~`route.size`. Same window as
     * [project] (±[SEARCH_CELLS]: with a cell of one reach, a point anywhere in its cell still has
     * ≥ reach of clearance in every direction, so the true nearest segment ≤ reach away is inside).
     */
    fun segmentsNear(p: LatLng): Int = gatherNear(p, newScratch())

    private fun key(latIdx: Int, lngIdx: Int): Long =
        (latIdx.toLong() shl 32) xor (lngIdx.toLong() and 0xffffffffL)

    private companion object {
        // Cell size = reach, so a point can sit up to one cell from its own cell's far edge; ±2
        // cells then guarantees ≥ reach of clearance from the point in every direction (incl.
        // diagonals), making the indexed nearest identical to the full scan for any in-reach point.
        const val SEARCH_CELLS = 2
        fun floorCell(v: Double, size: Double): Int = Math.floor(v / size).toInt()
    }
}

/**
 * Decode a Google encoded polyline (precision 5) to a list of (lat, lng) pairs.
 * karoo-ext delivers the route as a precision-5 encoded polyline.
 */
fun decodePolyline(encoded: String, precision: Int = 5): List<Pair<Double, Double>> {
    val factor = Math.pow(10.0, precision.toDouble())
    val points = ArrayList<Pair<Double, Double>>()
    var index = 0
    var lat = 0
    var lng = 0

    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        points.add(Pair(lat / factor, lng / factor))
    }
    return points
}
