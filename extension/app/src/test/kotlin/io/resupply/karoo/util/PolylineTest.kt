package io.resupply.karoo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {

    @Test
    fun `distanceToRoute signs the side by travel direction`() {
        // A route heading due north (increasing lat) at the equator.
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.02, 0.0))
        val cumulative = cumulativeDistances(route)

        // West of a northbound line is the left side (+1); east is the right (-1).
        val west = distanceToRoute(route, cumulative, LatLng(0.01, -0.001))
        val east = distanceToRoute(route, cumulative, LatLng(0.01, 0.001))
        assertEquals(1, west.side)
        assertEquals(-1, east.side)

        // A point on the line has no side.
        val on = distanceToRoute(route, cumulative, LatLng(0.01, 0.0))
        assertEquals(0, on.side)

        // Sanity: both off-line points sit roughly the same distance off the route.
        assertTrue(west.distance > 50.0 && east.distance > 50.0)
    }

    /**
     * The route grid must return the *identical* projection to the linear scan for any point
     * within the reach — it's an exact speedup, not an approximation. Sweeps a grid of query
     * points over a wiggly multi-segment route and diffs [RouteIndex.project] against
     * [distanceToRoute] field by field.
     */
    @Test
    fun `RouteIndex matches the linear distanceToRoute within reach`() {
        // A wiggly route so points fall near different segments, not one straight line.
        val route = (0..40).map { i ->
            LatLng(49.0 + i * 0.002, 8.0 + 0.004 * kotlin.math.sin(i * 0.5))
        }
        val cumulative = cumulativeDistances(route)
        val reach = 2_000.0
        val index = RouteIndex(route, cumulative, reach)

        var checked = 0
        for (la in 0..30) for (lo in 0..30) {
            val p = LatLng(49.0 + la * 0.0028, 7.99 + lo * 0.0028)
            val ref = distanceToRoute(route, cumulative, p)
            if (ref.distance > reach) continue // grid only guarantees points within reach
            val got = index.project(p)
            assertEquals("distance @($la,$lo)", ref.distance, got.distance, 1e-9)
            assertEquals("along @($la,$lo)", ref.along, got.along, 1e-9)
            assertEquals("side @($la,$lo)", ref.side, got.side)
            checked++
        }
        assertTrue("swept some in-reach points", checked > 20)
    }

    /** A segment longer than a cell must still be found from every cell it spans (it's
     *  registered in all of them), so a point beside its middle projects correctly. */
    @Test
    fun `RouteIndex handles a segment longer than a cell`() {
        // One ~11 km segment; reach 1 km → cell ~1 km, so the segment spans ~11 cells.
        val route = listOf(LatLng(49.0, 8.0), LatLng(49.1, 8.0))
        val cumulative = cumulativeDistances(route)
        val index = RouteIndex(route, cumulative, 1_000.0)

        // A point ~300 m east of the segment's midpoint.
        val p = LatLng(49.05, 8.0 + 300.0 / (METERS_PER_DEG_LAT * kotlin.math.cos(Math.toRadians(49.05))))
        val ref = distanceToRoute(route, cumulative, p)
        val got = index.project(p)
        assertEquals(ref.distance, got.distance, 1e-9)
        assertEquals(ref.along, got.along, 1e-9)
        assertEquals(ref.side, got.side)
    }
}
