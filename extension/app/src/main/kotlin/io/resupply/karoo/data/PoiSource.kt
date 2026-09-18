package io.resupply.karoo.data

/**
 * Whether the current POI set was built along a route or near the rider. Not stored — it's
 * *derived* from [ResupplyRepository.routeLengthMeters], the signal the whole pipeline already
 * treats as "route vs nearby" (the Waybook strip, the field progress math, the build). A
 * separate state field would be a second source of truth that could contradict the route
 * length; deriving it can't desync.
 */
enum class PoiSource { ROUTE, NEARBY }

/**
 * A route build sets a positive route length ([BuildController]); a /nearby build zeroes it.
 * So a non-zero length means the POIs are placed along a route, and zero means they're a
 * nearby set. (The empty/no-build case never reaches here — the fields show a prompt first.)
 */
fun poiSourceFor(routeLengthMeters: Double): PoiSource =
    if (routeLengthMeters > 0.0) PoiSource.ROUTE else PoiSource.NEARBY
