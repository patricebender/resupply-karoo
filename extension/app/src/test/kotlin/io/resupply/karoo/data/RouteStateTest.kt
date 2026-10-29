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
        assertEquals(RouteState.Detour, destinationNav().toRouteState())
    }

    @Test
    fun `keepsRouteLoaded stays true through a detour, false only when route removed`() {
        // The refresher gate: a detour must keep the route "loaded" so live nearby mode does NOT
        // engage mid-ride when the rider taps a pin. Only a genuine removal (Idle) drops the gate.
        val route = NavigationState.NavigatingRoute(
            routePolyline = "",
            routeDistance = 84_000.0,
            routeElevationPolyline = "",
            rejoinPolyline = null,
            rejoinDistance = null,
            name = "Alpen Tour",
            reversed = false,
            breadcrumb = false,
            pois = emptyList(),
            climbs = emptyList(),
        )
        assertEquals(true, route.keepsRouteLoaded())
        assertEquals(true, destinationNav().keepsRouteLoaded()) // detour: route still underneath
        assertEquals(false, NavigationState.Idle.keepsRouteLoaded())
    }

    private fun destinationNav() = NavigationState.NavigatingToDestination(
        destination = Symbol.POI(id = "p", lat = 0.0, lng = 0.0, type = Symbol.POI.Types.WATER),
        polyline = "",
        elevationPolyline = "",
        climbs = emptyList(),
    )
}
