package io.resupply.karoo.build

import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.PoiDatabase
import io.resupply.karoo.data.ResupplyConfig
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.data.RoadbookCache
import io.resupply.karoo.util.sha256
import timber.log.Timber

/**
 * The cache key for a route's built POI set. Folds in every input that determines what
 * [io.resupply.karoo.data.PoiQuery.queryCorridor] returns:
 *  - the route geometry (hash of the encoded polyline),
 *  - the corridor shape (smart distance, or the fixed detour radius),
 *  - the installed-region set (what the DB can return), and
 *  - the seed generation ([PoiDatabase.BUNDLED_DB_VERSION]).
 *
 * Render-time filters (enabled categories, safe-water) are deliberately EXCLUDED — those
 * narrow the built set at display time via [ResupplyConfig.showsPoi], so toggling them must
 * not force a requery. Any NEW input to `queryCorridor` MUST be added here, or a stale cached
 * set could be served / promoted for a route whose result has actually changed.
 *
 * Shared by [BuildController] (populate/lookup on build) and the extension's route-load cache
 * realization, so the two can't compute divergent keys for the same route.
 */
fun routeBuildKey(routePolyline: String, config: ResupplyConfig, installedRegions: Set<String>): String {
    val distance = if (config.smartDistance) "smart" else config.detourMeters.toString()
    val regionSet = installedRegions.sorted().joinToString(",")
    return "${sha256(routePolyline)}:$distance:$regionSet:${PoiDatabase.BUNDLED_DB_VERSION}"
}

/**
 * When a route loads, show its previously-built POIs *instantly* if we have them cached — no
 * tap, no rebuild. Looks up [RoadbookCache] by the route's [routeBuildKey]; on a hit it promotes
 * the stored set (see [promoteRoadbook]) and returns true. On a miss it does nothing and returns
 * false — the caller decides whether to clear the live set so the fields fall back to their
 * "Tap to build" prompt.
 *
 * Driven by the extension's nav watcher so the data field is ready the moment the route loads.
 */
suspend fun promoteCachedRoadbook(
    routePolyline: String,
    routeDistance: Double,
    config: ResupplyConfig,
    installedRegions: Set<String>,
    repository: ResupplyRepository,
    roadbookCache: RoadbookCache,
    configStore: ConfigStore,
): Boolean {
    val buildKey = routeBuildKey(routePolyline, config, installedRegions)
    val pois = roadbookCache.get(buildKey)?.takeIf { it.isNotEmpty() } ?: return false
    Timber.d("route load: realizing ${pois.size} cached POIs (no rebuild)")
    promoteRoadbook(pois, routePolyline, routeDistance, repository, configStore)
    return true
}

/**
 * Make [pois] the current roadbook for the route with the given [routePolyline]: publish them as
 * the live set, set the [routeDistance] so the strip/fields read it as ROUTE, and point the
 * derived favorites at this route (its stars, if any, reappear via the config flow). Shared by the
 * nav-watcher realization above and [BuildController]'s build cache hit, so both promote the same
 * way from the same inputs.
 */
suspend fun promoteRoadbook(
    pois: List<Poi>,
    routePolyline: String,
    routeDistance: Double,
    repository: ResupplyRepository,
    configStore: ConfigStore,
) {
    repository.setRouteLength(routeDistance)
    repository.setPois(pois)
    configStore.setCurrentRouteKey(sha256(routePolyline))
}
