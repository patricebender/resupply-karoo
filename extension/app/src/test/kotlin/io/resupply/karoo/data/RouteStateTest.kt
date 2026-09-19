package io.resupply.karoo.data

import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.Symbol
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

    @Test
    fun `navigating to a destination maps to Detour, not None`() {
        // Tapping a POI pin detours to a single destination; the route is still loaded
        // underneath, so this must NOT read as "no route" (which would wipe the roadbook).
        val nav = NavigationState.NavigatingToDestination(
            destination = Symbol.POI(id = "p", lat = 0.0, lng = 0.0, type = Symbol.POI.Types.WATER),
            polyline = "",
            elevationPolyline = "",
            climbs = emptyList(),
        )
        assertEquals(RouteState.Detour, nav.toRouteState())
    }
}
