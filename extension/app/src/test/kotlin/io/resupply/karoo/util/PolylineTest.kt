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
}
