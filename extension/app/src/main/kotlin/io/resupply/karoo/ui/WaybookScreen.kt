package io.resupply.karoo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.resupply.karoo.R
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ResupplyConfig
import io.resupply.karoo.data.OpeningHours
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.PoiSource
import io.resupply.karoo.data.aheadMetersFor
import io.resupply.karoo.data.behindMetersFor
import io.resupply.karoo.data.RouteState
import io.resupply.karoo.data.etaArrival
import io.resupply.karoo.data.formatEtaClock
import io.resupply.karoo.data.formatKm
import io.resupply.karoo.data.poiSourceFor
import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.haversine
import kotlin.math.abs

// Position-follow: advances within this many rows animate (smooth, normal progress); larger
// jumps snap instantly, so a discontinuity re-syncs the list without the range bracket
// visibly crawling toward the (instantly-moved) strip bike glyph.
private const val SMOOTH_FOLLOW_ROWS = 3

// True when the active theme is dark (dark surface). Drives the logo swap below.
@Composable
private fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * The Resupply squircle mark, themed: the dark-tile [R.drawable.ic_resupply] on the light theme,
 * the cream-tile [R.drawable.ic_resupply_light] on the dark theme, so the tile contrasts with the
 * surface either way. [ResupplyLoadingLogo] mirrors this swap for its animated variant.
 */
@Composable
private fun resupplyLogo(): Painter =
    painterResource(if (isDarkTheme()) R.drawable.ic_resupply_light else R.drawable.ic_resupply)

/**
 * The Waybook ROUTE view: a header with build + settings shortcuts and a live build
 * status line, the route distance strip, and a scrollable list of the POIs found along
 * the route. Tapping a row opens the place detail.
 */
@Composable
fun WaybookScreen(
    pois: List<Poi>,
    // The categories the rider currently wants shown. The build holds every category in
    // memory; this filters `pois` to the enabled subset at render time, so toggling a
    // category updates the overview instantly with no rebuild.
    enabledCategories: Set<Category>,
    // Narrow water to safe sources (taps, graveyards, explicitly-potable) at render time.
    // Only bites when the water category is enabled; the build keeps every water POI.
    safeWaterOnly: Boolean,
    routeLengthMeters: Double,
    // Live along-route position of the rider, or null when there's no route/live
    // stream. Drives the timeline marker, per-row distance-ahead, and the initial
    // scroll to the first POI ahead.
    progressMeters: Double?,
    // Rider's ride-average speed (m/s) for the per-row ETA + "open on arrival" cue. Null until
    // the first sample or with no live stream; the row then omits the ETA and falls back to the
    // now-relative open/closed badge only.
    avgSpeedMps: Double?,
    // Whether a route is loaded on the Karoo (and its name/distance). Drives the empty
    // state: route hero + "Find places" when loaded, a "find places near you" prompt when not.
    routeState: RouteState,
    // Rider location, for a nearby build's straight-line list distances. Null when unknown.
    riderLocation: LatLng?,
    // Nearby POIs are being auto-refreshed as the rider moves → the proximity band shows a
    // "Live" indicator.
    nearbyLive: Boolean,
    // Rider has paused route-less live search. Drives the empty face: paused → the "find live
    // resupply" invitation; not paused (actively searching) → "No places nearby".
    nearbyPaused: Boolean,
    // The detour radius (meters) — sets the nearby proximity band's right-edge scale.
    nearbyRadiusMeters: Int,
    buildState: BuildState,
    // The rider's starred POIs (ids) on the current roadbook, and whether the "favorites only"
    // filter is on. Route-mode only — both are ignored/hidden when there's no route.
    favoritePoiIds: Set<String>,
    favoritesOnly: Boolean,
    onToggleFavorite: (Poi, Boolean) -> Unit,
    onToggleFavoritesFilter: () -> Unit,
    onBuild: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPoi: (Poi) -> Unit,
    // Resolves a POI's hours (OSM, or a Google result already fetched this session) so
    // the list badge shows for both sources once known.
    hoursOf: (Poi) -> OpeningHours.Hours?,
    // Hoisted so the scroll position survives opening/closing the detail view.
    listState: LazyListState,
    // Whether the list should follow the live position (hoisted in MainActivity so it survives
    // a POI-detail round-trip; re-armed only on a fresh field-tap). While true, the list keeps
    // the first-POI-ahead at the top as the rider progresses.
    following: Boolean,
    // Called on the rider's first manual scroll, so the host can end following for this session.
    onUserScrolled: () -> Unit,
) {
    // Favorites are a route-mode feature: a nearby set is ephemeral, so the star toggles, the
    // filter, and the timeline stars are all hidden without a route.
    val routeMode = routeLengthMeters > 0.0

    // Show only the enabled categories. The build keeps every category in memory, so this is
    // the sole gate on what the overview renders — re-enabling a category surfaces it instantly.
    // We keep the raw [pois] to tell "nothing built" apart from "built, but every category is
    // toggled off", and keep this category-filtered set separate from the favorites-filtered one
    // so "no favorites yet" is distinguishable from "no categories" (each wants its own face).
    val categoryVisible = remember(pois, enabledCategories, safeWaterOnly) {
        pois.filter { poi ->
            val cat = Category.ofType(poi.type) ?: return@filter false
            cat in enabledCategories &&
                !(cat == Category.WATER && safeWaterOnly && !ResupplyConfig.isSafeWaterSource(poi.tags))
        }
    }

    // The final rendered set: category-filtered, then narrowed to favorites when the filter is
    // on (route mode only). This mirrors [ResupplyConfig.showsPoi] so the list agrees with the
    // map pins and the fields under the same filter.
    val visiblePois = remember(categoryVisible, routeMode, favoritesOnly, favoritePoiIds) {
        if (routeMode && favoritesOnly) categoryVisible.filter { it.id in favoritePoiIds }
        else categoryVisible
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            buildState = buildState,
            // When the body shows the big animated logo (first build, no pins yet),
            // keep the header status line quiet so there's only one progress cue. Keyed on the
            // built set (not the filtered one) so filtering all categories out doesn't yank the
            // header back to its pre-build look.
            showStatusLine = pois.isNotEmpty() || buildState !is BuildState.Building,
            // The header icon only appears once the list takes over the body. While the body
            // shows the big mark — nothing built (empty state) OR built-but-all-categories-off
            // (NoCategoriesState) — the header icon stays hidden so there's never two at once.
            // Keyed on the VISIBLE set, not the raw built set, so the all-off face counts as
            // "big mark showing".
            showIcon = visiblePois.isNotEmpty(),
            // Nearby auto-refresh active → header shows a "Live" chip (and no rebuild button,
            // which would be redundant when the set refreshes itself).
            nearbyLive = nearbyLive,
            // The favorites filter star, shown only in route mode. Hidden until there's a built
            // set with an enabled category to filter (nothing to narrow before then).
            showFavoritesFilter = routeMode && categoryVisible.isNotEmpty(),
            favoritesOnly = favoritesOnly,
            onToggleFavoritesFilter = onToggleFavoritesFilter,
            onOpenSettings = onOpenSettings,
        )
        HorizontalDivider()

        // Point the rider at Settings (not a pointless rebuild) whenever the built set can't
        // surface anything under the current category selection:
        //  - no categories enabled at all, OR
        //  - route mode with a built roadbook whose POIs are all in disabled categories (a rebuild
        //    re-runs the same query → same set; the fix is to enable a category, in Settings).
        // Live mode is excluded here: an empty visible set there means "nothing of interest nearby
        // right now", which [NearbyReadyState] handles with its keep-looking face — no Settings trip.
        val routeBuiltButFiltered = routeMode && pois.isNotEmpty() && categoryVisible.isEmpty()
        if (enabledCategories.isEmpty() || routeBuiltButFiltered) {
            NoCategoriesState(onOpenSettings)
            return@Column
        }

        // Categories resolve to POIs, but the favorites filter is on and nothing's starred yet →
        // a dedicated "no favorites" face with a one-tap "show all" escape, rather than a blank
        // list. Only reachable in route mode (the filter is route-only).
        if (categoryVisible.isNotEmpty() && visiblePois.isEmpty()) {
            NoFavoritesState(onShowAll = onToggleFavoritesFilter)
            return@Column
        }

        // No visible places: either nothing is built yet, or a set is built but none of it falls
        // under the enabled categories (a category is selected but has nothing nearby/along-route).
        // Both land on the mode's empty face — route hero + "Find places", or the nearby prompt /
        // "No places nearby" — rather than a blank list.
        if (visiblePois.isEmpty()) {
            EmptyState(
                routeState,
                buildState,
                hasFix = riderLocation != null,
                paused = nearbyPaused,
                onBuild = onBuild,
            )
            return@Column
        }

        // Past both empty guards: there IS a roadbook and at least one enabled category resolves
        // to POIs. From here on the list/strip/scroll render the visible (filtered) set only.
        @Suppress("NAME_SHADOWING")
        val pois = visiblePois

        // Route vs nearby, derived from the built route length (same signal the fields use).
        // A nearby set has no along-route positions, so the strip/scroll/detour cues don't
        // apply; distance is straight-line from the rider instead.
        val source = poiSourceFor(routeLengthMeters)

        // For a nearby build, keep only POIs still within the detour radius of the rider (the
        // cached set was filtered at fetch time, but the rider has since moved — drop the ones
        // now out of range so the list agrees with the data field and the radar band), and
        // order nearest-first. Route sets arrive pre-sorted along-route from PoiQuery.
        val sortedPois = if (source == PoiSource.NEARBY && riderLocation != null) {
            pois.map { it to haversine(riderLocation, LatLng(it.lat, it.lng)) }
                .filter { (_, d) -> d <= nearbyRadiusMeters.toDouble() }
                .sortedBy { (_, d) -> d }
                .map { (poi, _) -> poi }
        } else {
            pois
        }

        // Defer live churn to the LIST while the rider is interacting with it. A background
        // nearby refresh (or the continuous re-sort as they move) would otherwise shuffle rows
        // under their finger — jarring when they've scrolled down. So for a nearby set we
        // render a *snapshot* and only refresh it when they're back at the top and not
        // scrolling. (Opening a POI detail unmounts this screen entirely, so returning always
        // re-snapshots fresh — no separate guard needed for that.) Route sets don't
        // auto-refresh, so they render live as before. Map pins + data fields are NOT deferred
        // — only this list holds the rider's scroll focus.
        val interacting = listState.isScrollInProgress || listState.firstVisibleItemIndex > 0
        val displayPois = if (source == PoiSource.NEARBY) {
            val snapshot = remember { mutableStateOf(sortedPois) }
            if (!interacting) snapshot.value = sortedPois
            snapshot.value
        } else {
            sortedPois
        }

        // The along-route km span of the currently visible list window (first..last visible
        // row) — drives the range bracket on the strip. Reading the LAST visible row (not just
        // the first) is what lets the indicator reach the end of the route when scrolled to the
        // bottom; a first-only read saturates several rows short of the final POIs.
        // derivedStateOf so it only recomputes when the visible window changes.
        val visibleSpanMeters by remember(pois, routeLengthMeters) {
            derivedStateOf {
                if (routeLengthMeters <= 0.0) return@derivedStateOf null
                val visible = listState.layoutInfo.visibleItemsInfo
                val firstIdx = visible.firstOrNull()?.index ?: return@derivedStateOf null
                val lastIdx = visible.last().index
                val startM = pois.getOrNull(firstIdx)?.distancesAlongRoute?.firstOrNull()
                val endM = pois.getOrNull(lastIdx)?.distancesAlongRoute?.firstOrNull()
                if (startM == null || endM == null) return@derivedStateOf null
                // POIs arrive pre-sorted along-route, but clamp defensively so start <= end.
                minOf(startM, endM) to maxOf(startM, endM)
            }
        }

        // Nearby analog of [visibleSpanMeters]: the straight-line distance span of the visible
        // list window, feeding the SAME range bracket on the radar. Reads distances off the
        // rendered [displayPois] (nearest-first), so scrolling the list moves the bracket over the
        // proximity axis just as it does on the route timeline.
        val visibleNearbySpanMeters by remember(displayPois, riderLocation, source) {
            derivedStateOf {
                if (source != PoiSource.NEARBY || riderLocation == null) return@derivedStateOf null
                val visible = listState.layoutInfo.visibleItemsInfo
                val firstIdx = visible.firstOrNull()?.index ?: return@derivedStateOf null
                val lastIdx = visible.last().index
                val startPoi = displayPois.getOrNull(firstIdx) ?: return@derivedStateOf null
                val endPoi = displayPois.getOrNull(lastIdx) ?: return@derivedStateOf null
                val startM = haversine(riderLocation, LatLng(startPoi.lat, startPoi.lng))
                val endM = haversine(riderLocation, LatLng(endPoi.lat, endPoi.lng))
                minOf(startM, endM) to maxOf(startM, endM)
            }
        }

        // The band above the list: a route timeline for a route build, or a proximity "radar"
        // for a nearby build (rider at the left edge, POIs by straight-line distance). Both
        // reuse RouteStrip; the nearby mode also carries the "Live" indicator, which has room
        // here that the header lacks.
        when (source) {
            PoiSource.ROUTE -> {
                RouteStrip(
                    pois = pois,
                    routeLengthMeters = routeLengthMeters,
                    progressMeters = progressMeters,
                    listStartMeters = visibleSpanMeters?.first,
                    listEndMeters = visibleSpanMeters?.second,
                    // Little yellow stars above favorite dots (route timeline only).
                    favoritePoiIds = favoritePoiIds,
                )
                HorizontalDivider()
            }
            PoiSource.NEARBY -> {
                // Only show the radar once we can place dots (need a rider fix); otherwise the
                // list alone carries the screen until the first location arrives.
                if (riderLocation != null) {
                    RouteStrip(
                        pois = displayPois,
                        routeLengthMeters = 0.0,
                        riderLocation = riderLocation,
                        nearbyRadiusMeters = nearbyRadiusMeters,
                        // Straight-line span of the visible list window → the shared range bracket.
                        listStartMeters = visibleNearbySpanMeters?.first,
                        listEndMeters = visibleNearbySpanMeters?.second,
                    )
                    HorizontalDivider()
                }
            }
        }

        // Mid-ride the list FOLLOWS the live position: the first POI still ahead is kept at the
        // top so "what's next" is always in view as the rider progresses — the list moves with
        // the bike glyph on the strip. The FIRST manual scroll ends following for good (the rider
        // is now exploring ahead; don't yank them back), via [onUserScrolled] on the hoisted
        // flag. It re-engages only on a fresh field-tap entry — NOT on returning from a POI
        // detail, where [following] survives the round-trip because it's hoisted in MainActivity.
        val canFollow = progressMeters != null && pois.isNotEmpty() && routeLengthMeters > 0.0

        // The row to keep at the top: the first POI still ahead of the rider (fall back to the
        // last row once all are passed). A PLAIN val, recomputed every recomposition — which is
        // how it tracks [progressMeters], a plain param the parent re-hands us on each position
        // tick. (derivedStateOf/snapshotFlow can't observe a plain param — only a snapshot State
        // — so they'd freeze at the first value; that was the "stuck" bug.) The follow effect
        // below is keyed on this, so it only *acts* when the index actually changes → one scroll
        // per POI crossing, not per metre.
        val followIndex = progressMeters?.let { p ->
            pois.indexOfFirst { aheadMetersFor(it, p) != null }
                .let { if (it < 0) pois.lastIndex else it }
        } ?: -1

        // A hand drag (not our programmatic scroll — that emits no DragInteraction) ends
        // following via the host, which no-ops if already off — so this is safe to leave always
        // subscribed (no need to re-key on `following`, which would open a teardown race).
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) onUserScrolled()
            }
        }

        // The follow itself: keyed on the target row + following, so it re-runs exactly when the
        // rider crosses into a new POI (or following re-engages) — and moves there in THIS
        // coroutine, so a re-key cancels an in-flight scroll instead of stacking animations.
        // Near advances animate, large jumps snap (see [SMOOTH_FOLLOW_ROWS]).
        LaunchedEffect(followIndex, following, canFollow) {
            if (!canFollow || !following || followIndex < 0) return@LaunchedEffect
            val current = listState.firstVisibleItemIndex
            if (followIndex == current && listState.firstVisibleItemScrollOffset == 0) return@LaunchedEffect
            if (abs(followIndex - current) <= SMOOTH_FOLLOW_ROWS) listState.animateScrollToItem(followIndex)
            else listState.scrollToItem(followIndex)
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(displayPois, key = { it.id }) { poi ->
                // Route: distance ahead/behind along the route. Nearby: straight-line from the
                // rider (shown in the "ahead" slot; a nearby POI is never "behind").
                val ahead: Double?
                val behind: Double?
                if (source == PoiSource.NEARBY) {
                    ahead = riderLocation?.let { haversine(it, LatLng(poi.lat, poi.lng)) }
                    behind = null
                } else {
                    ahead = progressMeters?.let { aheadMetersFor(poi, it) }
                    // Known progress but no ahead-crossing ⇒ behind the rider; how far back.
                    behind = progressMeters?.takeIf { ahead == null }?.let { behindMetersFor(poi, it) }
                }
                // "Open on arrival": for a POI still ahead on a route, estimate the arrival
                // clock time from its distance-ahead + the rider's average speed, then judge
                // its hours against *that* time (not "now"). Route-only and only while ahead —
                // a passed or nearby POI has no meaningful along-route arrival. Null (no ETA
                // line) until there's a real moving average — before the ride starts we don't
                // fabricate one (see [etaArrival]).
                val arrival = if (source == PoiSource.ROUTE && ahead != null) {
                    etaArrival(ahead, avgSpeedMps)
                } else {
                    null
                }
                PoiRow(
                    poi = poi,
                    hours = hoursOf(poi),
                    hasRoute = routeLengthMeters > 0,
                    aheadMeters = ahead,
                    behindMeters = behind,
                    arrival = arrival,
                    onClick = { onOpenPoi(poi) },
                    // Route mode: a per-row star (trailing) to favorite straight from the list.
                    // Live mode: no star (favorites are route-only) — the chevron stays instead.
                    favoritable = routeMode,
                    isFavorite = poi.id in favoritePoiIds,
                    onToggleFavorite = { fav -> onToggleFavorite(poi, fav) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

@Composable
private fun Header(
    buildState: BuildState,
    showStatusLine: Boolean,
    showIcon: Boolean,
    nearbyLive: Boolean,
    showFavoritesFilter: Boolean,
    favoritesOnly: Boolean,
    onToggleFavoritesFilter: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Wordmark: the full squircle icon (dark tile behind the R) + "RESUPPLY" as themed
        // text. The squircle is used (not the transparent mark) so the cream R keeps its
        // dark backing and stays readable on both the light and dark Karoo themes — the
        // same reason the placeholder uses it. Image (not Icon) so it keeps its own
        // route/dot colours instead of being flattened to a single tint. The text uses
        // onSurface so it flips with the theme.
        //
        // The icon is absent until a build lands places; then it glides in from the left
        // (slide + fade) while "RESUPPLY" stays anchored — so the mark reads as arriving,
        // not as the whole wordmark shifting. expandHorizontally lets the text slide over
        // to make room instead of jumping. Slower, eased motion keeps it modern, not cute.
        AnimatedVisibility(
            visible = showIcon,
            enter = fadeIn(animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)) +
                slideInHorizontally(
                    animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                    initialOffsetX = { -it },
                ) +
                expandHorizontally(
                    animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start,
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)) +
                shrinkHorizontally(
                    animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = resupplyLogo(),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
        }
        Text(
            "RESUPPLY",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            // Transient build feedback only (the spinner).
            if (showStatusLine && buildState is BuildState.Building) BuildStatusLine(buildState)
        }
        // Live chip: nearby POIs auto-refresh as the rider moves, so there's no manual rebuild
        // to offer — the chip replaces the old rebuild button, signalling "tracking you". A
        // pulsing dot carries the motion; the header (unlike the band) has room for it beside
        // the settings gear. Manual rebuild lives in Settings for the route case.
        if (nearbyLive) LiveChip()
        // The favorites filter (route mode only): an outlined star that fills yellow when the
        // list is narrowed to favorites. Sits just left of Settings so both live in the header.
        if (showFavoritesFilter) {
            StarToggle(
                filled = favoritesOnly,
                contentDescription = if (favoritesOnly) "Show all places" else "Show favorites only",
                onClick = onToggleFavoritesFilter,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

/**
 * The shared favorites star toggle: an outlined star that crossfades to a filled yellow star
 * when [filled], with a small scale bump on change so the tap reads without being cute. Used by
 * the header filter and the per-row favorite control so both animate identically.
 */
@Composable
private fun StarToggle(
    filled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (filled) FavoriteYellow else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "starTint",
    )
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (filled) 1.15f else 1f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "starScale",
    )
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale),
        )
    }
}

/**
 * A slow-breathing green dot — the shared "live" motif. Used in the header [LiveChip] and the
 * enable-live invitation, so both read as the same feature. Confined to the dot so motion
 * stays subtle on a bike computer glanced at speed.
 */
@Composable
private fun PulsingDot(size: Dp, color: Color = openGreen) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "livePulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "livePulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * The header "Live" chip: a slow-pulsing green dot + "Live", shown while nearby POIs
 * auto-refresh. Sits where the rebuild button used to — that action is redundant in live mode.
 */
@Composable
private fun LiveChip() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        PulsingDot(size = 8.dp)
        Spacer(Modifier.size(5.dp))
        Text(
            "Live",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = openGreen,
            maxLines = 1,
        )
    }
}

/**
 * The header's primary line, only used for transient build feedback: the current build
 * phase, with a spinner. The steady-state place count lives in the timeline strip below, so
 * idle/success leave this line blank — the logo and action icons carry the header on their
 * own. Errors are NOT shown here: the header is too narrow to render a message legibly (it'd
 * ellipsize to an unreadable "No…"), and build errors already surface as a system
 * notification, while the no-places/no-fix cases land on the overview's own prompt state.
 */
@Composable
private fun BuildStatusLine(state: BuildState) {
    when (state) {
        is BuildState.Building -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                state.phase,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is BuildState.Error, is BuildState.Success, is BuildState.Idle -> Unit
    }
}

@Composable
private fun PoiRow(
    poi: Poi,
    hours: OpeningHours.Hours?,
    hasRoute: Boolean,
    // Meters still to ride to reach this POI; null when unknown or already passed.
    aheadMeters: Double?,
    // Meters behind the rider once passed (≥0); null when still ahead / no position.
    behindMeters: Double?,
    // Estimated arrival clock time for a POI ahead on a route; null when not applicable (nearby,
    // passed, or no live speed). Drives the "open on arrival" ETA line.
    arrival: java.util.Calendar?,
    onClick: () -> Unit,
    // Route mode: show the trailing star to favorite from the list. Live mode: false → the
    // chevron shows instead (favorites are route-only).
    favoritable: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (Boolean) -> Unit,
) {
    val style = styleForType(poi.type)
    val passed = behindMeters != null
    // Passed POIs recede further so the ahead ones stand out, but stay tappable (scroll
    // up to backtrack to a water stop you rode past).
    val rowAlpha = if (passed) 0.35f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(rowAlpha)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left rail: the category icon (with the open/closed status badge) stacked over
        // the route distance to/from this POI and the detour, so all three live on the narrow
        // left edge and the name/type/ETA text gets the full remaining width (keeping rows to
        // at most three lines on the right).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusIcon(style = style, hours = hours)
            DistanceLabel(aheadMeters = aheadMeters, behindMeters = behindMeters)
            // Detour (only meaningful along a route): a compact "+120m" under the distance.
            if (hasRoute && poi.detourMeters > 0) {
                Text(
                    "+${formatDistance(poi.detourMeters)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                poi.name ?: "Unnamed",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Line 2: type. Line 3 (when closed): "opens Mon 08:00".
            TypeAndOpensLine(hours = hours, typeLabel = labelForPoi(poi))
            // "Open on arrival": ETA clock time, tinted by whether the POI will be open then —
            // green (open), amber (a close call), grey (no hours to judge).
            if (arrival != null) EtaLine(arrival = arrival, hours = hours)
        }
        // Route mode: a star to favorite this POI straight from the list (the row itself still
        // opens the detail). Live mode: the plain chevron, since favorites don't apply there.
        if (favoritable) {
            StarToggle(
                filled = isFavorite,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = { onToggleFavorite(!isFavorite) },
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The category icon disc with the open/closed status as a small badge dot in the
 * bottom-right corner (green=open, red=closed, grey=seasonal). Nothing is drawn for
 * unknown/no hours, so we never imply a status we don't have. The dot has a white ring
 * so it stays legible against any icon color.
 */
@Composable
private fun StatusIcon(style: CategoryStyle, hours: OpeningHours.Hours?) {
    val status = remember(hours) { hours?.status() }
    val seasonal = hours?.rawFallback != null
    val badge: Color? = when {
        seasonal -> seasonalGrey
        status?.state == OpeningHours.OpenState.OPEN -> openGreen
        status?.state == OpeningHours.OpenState.CLOSED -> closedRed
        else -> null
    }
    Box(modifier = Modifier.size(40.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(style.color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                style.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        badge?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(it),
            )
        }
    }
}

/**
 * Compact route-distance label sitting under the icon on the left rail: bold primary
 * `4.2km` for a POI ahead (distance to reach it), or a dim `↓ 2.1km` for one already
 * passed (a down-arrow reads as "behind you" without spending a second line). Nothing
 * when there's no live position. A little top padding separates it from the icon.
 */
@Composable
private fun DistanceLabel(aheadMeters: Double?, behindMeters: Double?) {
    when {
        aheadMeters != null -> Text(
            formatKm(aheadMeters),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
        behindMeters != null -> Text(
            "↓ ${formatKm(behindMeters)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * "Open on arrival" line: the estimated arrival clock time (e.g. "ETA 14:35"), tinted by
 * whether the POI will be open then — green (open), amber (a close call, arriving near an
 * open/close edge), grey (no hours to judge, or arriving while shut). Judges the hours against
 * the *arrival* time, not "now", so the rider sees whether it's worth aiming for. The word
 * "closed" is only spelled out when we're confident; a close call stays terse to avoid crying
 * wolf on a jittery average speed.
 */
@Composable
private fun EtaLine(arrival: java.util.Calendar, hours: OpeningHours.Hours?) {
    val status = remember(hours, arrival.timeInMillis) {
        hours?.arrivalStatus(arrival) ?: OpeningHours.ArrivalStatus.UNKNOWN
    }
    val (color, suffix) = when (status) {
        OpeningHours.ArrivalStatus.OPEN -> openGreen to " · open"
        OpeningHours.ArrivalStatus.CLOSE_CALL -> EtaCloseCallAmber to " · close"
        OpeningHours.ArrivalStatus.CLOSED -> closedRed to " · closed"
        OpeningHours.ArrivalStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant to ""
    }
    Text(
        "ETA ${formatEtaClock(arrival)}$suffix",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Type label; when closed, the "opens …" text drops to its own line so it stays
 * readable on the narrow display. The open/closed status itself now lives on the icon
 * badge (see [StatusIcon]), not here.
 */
@Composable
private fun TypeAndOpensLine(hours: OpeningHours.Hours?, typeLabel: String) {
    val status = remember(hours) { hours?.status() }
    Column {
        Text(
            typeLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (status?.state == OpeningHours.OpenState.CLOSED) {
            status.opensAtLabel(todayIndex())?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Shown when a roadbook is built but every category is toggled off, so the filtered list is
 * empty. Distinct from [EmptyState]: the POIs exist in memory, so a rebuild would be pointless —
 * the fix is to re-enable a category. Points the rider straight at Settings instead of offering a
 * build. Keeps the same 72.dp mark slot + centered layout as the other empty faces so switching
 * to/from it doesn't jump.
 */
@Composable
private fun NoCategoriesState(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = resupplyLogo(),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No categories selected",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        // Same dark pill as the build CTAs, but with a gear — it opens Settings (where the
        // category chips are) rather than triggering a build; nothing to rebuild, only re-enable.
        DarkPillButton(onClick = onOpenSettings, icon = Icons.Filled.Settings) {
            Text("Choose categories", style = MaterialTheme.typography.titleSmall, color = ButtonCream)
        }
    }
}

/**
 * Shown when the favorites filter is on but nothing is starred yet. Mirrors [NoCategoriesState]:
 * same 72dp mark slot + centered layout so switching to/from it doesn't jump. The CTA turns the
 * filter back off (there's nothing to rebuild — the POIs are in memory, just filtered to an empty
 * favorites set), so one tap returns to the full list where the rider can star places.
 */
@Composable
private fun NoFavoritesState(onShowAll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same Resupply mark + 72dp slot as the other placeholder faces (not a big star — three
        // stars on one screen read as broken). The favorites cue is the copy and the row/header
        // stars, not a hero star here.
        Image(
            painter = resupplyLogo(),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No favorites yet",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap the star on a place to build your shortlist",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        DarkPillButton(onClick = onShowAll, icon = Icons.Filled.Place) {
            Text("Show all places", style = MaterialTheme.typography.titleSmall, color = ButtonCream)
        }
    }
}

/**
 * The no-places body. Two faces, chosen by whether a route is loaded — but both keep the
 * mark in the exact same slot/size so switching between them (and the build animation)
 * never jumps:
 *  - a route is loaded → [RouteReadyState]: the route's name + distance and a single
 *    "Find places" action (which animates the mark in place while building).
 *  - nothing loaded → [NearbyReadyState]: a "find places near you" prompt with a "Find
 *    places nearby" action — a build with no route falls back to POIs around the rider.
 * While a build is running we always show the route-ready face (the mark is mid-animation),
 * regardless of the latest route signal, so the animation isn't yanked away.
 */
@Composable
private fun EmptyState(
    routeState: RouteState,
    buildState: BuildState,
    // Whether a GPS fix is available. With no route, the nearby face waits on this: until a fix
    // lands it shows "Waiting for GPS signal" with the Find button disabled.
    hasFix: Boolean,
    // Rider paused live search → the nearby face shows the invitation instead of "No places nearby".
    paused: Boolean,
    onBuild: () -> Unit,
) {
    val loaded = routeState as? RouteState.Loaded
    // Pick the face by whether a ROUTE is loaded, not by whether a build is running: a nearby
    // build (no route) must stay on the nearby face and morph in place, or the button would
    // vanish and be replaced by the route-oriented "Tracing your route…" copy — an abrupt swap
    // between two different layouts. Each face animates its own mark while building.
    if (loaded != null) {
        RouteReadyState(loaded, buildState, onBuild)
    } else {
        NearbyReadyState(buildState, hasFix, paused, onBuild)
    }
}

/**
 * Route loaded, nothing built yet: the route's name as the hero, its distance below, and the
 * [FindPlacesButton] dark-pill CTA — same style as the nearby face. While building, the mark
 * animates in place (route traced, waypoints lighting up), the title swaps to the live build
 * phase, the distance line stays put, and the button dims + goes inert. [route] may be null only
 * transiently while a build runs before the signal has settled.
 */
@Composable
private fun RouteReadyState(route: RouteState.Loaded?, buildState: BuildState, onBuild: () -> Unit) {
    val building = buildState is BuildState.Building
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same slot, same size — only the renderer changes when a build starts, so the
        // static squircle appears to spring to life rather than being replaced.
        if (building) {
            ResupplyLoadingLogo(size = 72.dp)
        } else {
            Image(
                painter = resupplyLogo(),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (building) {
                (buildState as BuildState.Building).phase
            } else {
                route?.name?.takeIf { it.isNotBlank() } ?: "Your route"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        // Distance line stays put while building — only the title above swaps to the phase, so
        // the block barely changes between idle and building (matching the nearby face's calm).
        Text(
            routeSubtitle(route),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        // The primary action: find places along the loaded route. Same dark pill as the nearby
        // CTA; while building it dims + goes inert (the title above carries the phase), matching
        // the nearby face.
        FindPlacesButton(onClick = onBuild, enabled = !building)
    }
}

/** The route's distance line, with a "· reversed" note when riding it backwards. */
private fun routeSubtitle(route: RouteState.Loaded?): String {
    val km = route?.distanceMeters?.takeIf { it > 0.0 }?.let { formatKm(it) }
    return when {
        km != null && route.reversed -> "$km · reversed"
        km != null -> km
        else -> "Ready to search"
    }
}

/**
 * No route loaded: the live-mode face, which reflects the current state so the rider always knows
 * where they stand:
 *  - no GPS fix yet ([hasFix] false) → a "Waiting for GPS signal" face (the pulsing mark carries
 *    the "working" motion), Find button disabled until a fix lands. No error, no timeout — the
 *    moment the live location flow emits, the button enables and they can search.
 *  - [BuildState.Building] → the mark animates and the title carries the phase; button inert.
 *  - [paused] (live search off, has fix) → the "find live resupply" invitation; the button resumes.
 *  - otherwise (live, has fix, nothing visible) → "No places nearby": a calm face that reassures
 *    we'll keep looking as they ride. Reached both when the search found nothing AND when it found
 *    only POIs outside the enabled categories — either way there's nothing to show here.
 * All faces keep the mark in the same 72.dp slot so switching between them (and the build
 * animation) never jumps. The CTA both starts/resumes a build and previews the header's "live" chip.
 */
@Composable
private fun NearbyReadyState(buildState: BuildState, hasFix: Boolean, paused: Boolean, onBuild: () -> Unit) {
    val building = buildState is BuildState.Building
    // No fix yet: we're waiting on GPS. The button stays disabled until one arrives.
    val waitingForGps = !hasFix && !building
    // Live and searching (not paused, not waiting, not building) but we reached the empty face →
    // nothing nearby resolves under the current categories. Reassure rather than imply an error.
    val noneNearby = hasFix && !paused && !building
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same slot/size as the idle mark — only the renderer changes when working (building, or
        // waiting on a fix), so the static squircle springs to life in place rather than swapped.
        if (building || waitingForGps) {
            ResupplyLoadingLogo(size = 72.dp)
        } else {
            Image(
                painter = resupplyLogo(),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        // The title tracks the state: waiting on GPS, the live build phase, "No places nearby"
        // when live but nothing resolves, else the paused invitation. The pulsing mark above
        // carries the motion, so the title itself is static.
        Text(
            when {
                waitingForGps -> "Waiting for GPS signal…"
                buildState is BuildState.Building -> buildState.phase
                noneNearby -> "No places nearby"
                else -> "Find places near you"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Live but empty: reassure it's not broken — we keep looking as they ride, so a POI-free
        // spot (or one with nothing in the chosen categories) needs no action.
        if (noneNearby) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing within range here — we'll keep looking as you ride.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        // The CTA IS the "enable live" affordance and the hero of the screen: a near-black pill
        // echoing the logo tile above, with the pulsing green "live" motif carried inline so the
        // button previews the header chip it lights up. Enabled only when a tap does something the
        // rider can't already rely on: starting/resuming live search (the paused invitation). It's
        // inert while building, while waiting for a fix (nothing to search around yet), and in the
        // "No places nearby" state — there live search is already running and auto-refreshing, so a
        // manual re-search would be a no-op that contradicts the "we'll keep looking" copy.
        FindLiveResupplyButton(onClick = onBuild, enabled = !building && !waitingForGps && !noneNearby)
    }
}

// The logo tile is a near-black deep teal-green; reusing it ties the CTA to the mark above.
private val ButtonTop = Color(0xFF16221F)
private val ButtonBottom = Color(0xFF0B1412)
private val ButtonCream = Color(0xFFF2EDE3)   // the logo's "R" cream — button label + icon
private val LiveGreen = Color(0xFF66BB6A)      // brighter than OpenGreen so it pops on near-black

/**
 * The shared hero CTA shell for the empty states: a dark, pill-shaped button matching the logo
 * tile, with a subtle top-to-bottom gradient for depth and a cream location pin. While a build
 * runs it dims and goes inert (the title above carries the phase). Callers supply the label via
 * [label] so the wording fits the mode — "Find places" along a route, the live variant nearby.
 */
@Composable
private fun DarkPillButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    // The leading glyph — a location pin for the build CTAs, a gear for the "choose categories"
    // action (which opens Settings, not a build).
    icon: ImageVector = Icons.Filled.Place,
    label: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f) // dim + inert while a build runs
            .clip(RoundedCornerShape(50))
            .background(Brush.verticalGradient(listOf(ButtonTop, ButtonBottom)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = ButtonCream,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(9.dp))
        label()
    }
}

/**
 * The no-route CTA: [DarkPillButton] reading "Find live resupply", where "live" keeps its pulsing
 * green dot + bold-green [PulsingDot] motif, so pressing it visibly continues into the header chip.
 */
@Composable
private fun FindLiveResupplyButton(onClick: () -> Unit, enabled: Boolean = true) {
    DarkPillButton(onClick = onClick, enabled = enabled) {
        Text("Find ", style = MaterialTheme.typography.titleSmall, color = ButtonCream)
        PulsingDot(size = 8.dp, color = LiveGreen)
        Spacer(Modifier.size(5.dp))
        Text(
            "live",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = LiveGreen,
        )
        Text(" resupply", style = MaterialTheme.typography.titleSmall, color = ButtonCream)
    }
}

/** The route CTA: the same dark pill, plain "Find places" — a one-shot search along the route. */
@Composable
private fun FindPlacesButton(onClick: () -> Unit, enabled: Boolean = true) {
    DarkPillButton(onClick = onClick, enabled = enabled) {
        Text("Find places", style = MaterialTheme.typography.titleSmall, color = ButtonCream)
    }
}
