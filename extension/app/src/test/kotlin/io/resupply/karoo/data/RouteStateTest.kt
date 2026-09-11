package io.resupply.karoo.data

import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteStateTest {

    @Test
    fun `navigating a route maps to Loaded with name and distance`() {
        val nav = NavigationState.NavigatingRoute(
            routePolyline = "",
            routeDistance = 84_000.0,
            routeElevationPolyline = "",
            rejoinPolyline = null,
            rejoinDistance = null,
            name = "Alpen Tour",
            reversed = true,
            breadcrumb = false,
            pois = emptyList(),
            climbs = emptyList(),
        )

        assertEquals(
            RouteState.Loaded(name = "Alpen Tour", distanceMeters = 84_000.0, reversed = true),
            nav.toRouteState(),
        )
    }

    @Test
    fun `idle maps to None`() {
        assertEquals(RouteState.None, NavigationState.Idle.toRouteState())
    }
}
