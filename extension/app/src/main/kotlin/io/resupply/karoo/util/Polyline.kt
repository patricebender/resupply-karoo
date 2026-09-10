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
 */
fun distanceToRoute(
    route: List<LatLng>,
    cumulative: DoubleArray,
    p: LatLng,
): RouteProjection {
    val mPerDegLat = METERS_PER_DEG_LAT
    val mPerDegLng = METERS_PER_DEG_LAT * cos(Math.toRadians(p.lat))
    val px = p.lng * mPerDegLng
    val py = p.lat * mPerDegLat

    var best = Double.POSITIVE_INFINITY
    var bestAlong = 0.0
    var bestSide = 0
    for (i in 1 until route.size) {
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
