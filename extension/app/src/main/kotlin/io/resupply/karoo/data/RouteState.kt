package io.resupply.karoo.data

import io.hammerhead.karooext.models.OnNavigationState.NavigationState

/**
 * Whether a route is currently loaded on the Karoo, and if so its identity. Observed live
 * (see [io.resupply.karoo.util.navStateFlow]) so both the app landing screen and the data
 * field can tell "route ready" from "nothing loaded" — a roadbook can only be built along a
 * route, so we never offer a build without one.
 */
sealed interface RouteState {
    /** Not observed yet (before the first nav event / connection). */
    data object Unknown : RouteState

    /** Nav is idle or navigating a climb — nothing we can build a roadbook along. */
    data object None : RouteState

    /**
     * Navigating to a single destination (e.g. the rider tapped a POI pin to detour to it).
     * The Karoo drops the route context from this event, but the original route is still loaded
     * underneath and will resume when the detour ends — so we keep the current roadbook rather
     * than treating this as "route gone". Distinct from [None] so the fields can say "on a
     * detour" instead of the no-route prompt.
     */
    data object Detour : RouteState

    /** A route is being navigated. [name]/[distanceMeters] drive the landing hero. */
    data class Loaded(
        val name: String?,
        val distanceMeters: Double,
        val reversed: Boolean,
    ) : RouteState
}

/**
 * Map a raw Karoo nav state to our [RouteState]. Only [NavigationState.NavigatingRoute]
 * carries a polyline we can build a roadbook along; a [NavigationState.NavigatingToDestination]
 * is a temporary detour to a tapped waypoint ([RouteState.Detour]); idle/climb are
 * [RouteState.None].
 */
fun NavigationState.toRouteState(): RouteState = when (this) {
    is NavigationState.NavigatingRoute -> RouteState.Loaded(
        name = name,
        distanceMeters = routeDistance,
        reversed = reversed,
    )
    is NavigationState.NavigatingToDestination -> RouteState.Detour
    else -> RouteState.None
}
