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

    /** Nav is idle or navigating something we can't build along (a bare destination/climb). */
    data object None : RouteState

    /** A route is being navigated. [name]/[distanceMeters] drive the landing hero. */
    data class Loaded(
        val name: String?,
        val distanceMeters: Double,
        val reversed: Boolean,
    ) : RouteState
}

/**
 * Map a raw Karoo nav state to our [RouteState]. Only [NavigationState.NavigatingRoute]
 * carries a polyline we can build a roadbook along; everything else (idle, a plain
 * destination, a climb) is [RouteState.None].
 */
fun NavigationState.toRouteState(): RouteState = when (this) {
    is NavigationState.NavigatingRoute -> RouteState.Loaded(
        name = name,
        distanceMeters = routeDistance,
        reversed = reversed,
    )
    else -> RouteState.None
}
