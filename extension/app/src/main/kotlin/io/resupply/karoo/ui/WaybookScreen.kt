package io.resupply.karoo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.resupply.karoo.R
import io.resupply.karoo.build.BuildState
import io.resupply.karoo.data.OpeningHours
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.aheadMetersFor
import io.resupply.karoo.data.behindMetersFor
import io.resupply.karoo.data.RouteState
import io.resupply.karoo.data.formatKm

/**
 * The Waybook ROUTE view: a header with build + settings shortcuts and a live build
 * status line, the route distance strip, and a scrollable list of the POIs found along
 * the route. Tapping a row opens the place detail.
 */
@Composable
fun WaybookScreen(
    pois: List<Poi>,
    routeLengthMeters: Double,
    // Live along-route position of the rider, or null when there's no route/live
    // stream. Drives the timeline marker, per-row distance-ahead, and the initial
    // scroll to the first POI ahead.
    progressMeters: Double?,
    // Whether a route is loaded on the Karoo (and its name/distance). Drives the empty
    // state: route hero + "Find places" when loaded, a "load a route" explainer when not.
    routeState: RouteState,
    buildState: BuildState,
    onBuild: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPoi: (Poi) -> Unit,
    // Resolves a POI's hours (OSM, or a Google result already fetched this session) so
    // the list badge shows for both sources once known.
    hoursOf: (Poi) -> OpeningHours.Hours?,
    // Hoisted so the scroll position survives opening/closing the detail view.
    listState: LazyListState,
    // Hoisted guard (see MainActivity): true once the initial "scroll to first POI
    // ahead" has run, so returning from a detail view doesn't yank the user back.
    didInitialScroll: MutableState<Boolean>,
    // Bumped by MainActivity on every fresh field-tap entry. Re-keys the auto-scroll so a
    // re-entry snaps back to the current position even when [progressMeters] hasn't
    // changed (the guard is reset alongside this, so it fires exactly once per entry).
    reentryKey: Int,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            buildState = buildState,
            // When the body shows the big animated logo (first build, no pins yet),
            // keep the header status line quiet so there's only one progress cue.
            showStatusLine = pois.isNotEmpty() || buildState !is BuildState.Building,
            // The header icon only appears once a build has landed places. While empty
            // the big body logo is the sole icon; when the list takes over, the squircle
            // glides into the header from the left. So there's never two icons at once.
            showIcon = pois.isNotEmpty(),
            // No build affordance until there's something to build along: hide the header
            // refresh action while empty and no route is loaded. Once pois exist, or a route
            // is loaded, the action is meaningful again.
            showBuild = pois.isNotEmpty() || routeState is RouteState.Loaded,
            onBuild = onBuild,
            onOpenSettings = onOpenSettings,
        )
        HorizontalDivider()

        if (pois.isEmpty()) {
            // No places yet: either a route is loaded (show its hero + "Find places") or
            // nothing is (a calm "load a route" explainer, no build affordance). Both keep
            // the mark in the same slot so a build animates in place without a jump.
            EmptyState(routeState, buildState, onBuild)
            return@Column
        }

        // The along-route km of the row at the top of the list — drives the floating
        // km pill on the strip so it tracks where the list is as you scroll.
        // derivedStateOf so it only recomputes when the visible window changes.
        val topVisibleMeters by remember(pois, routeLengthMeters) {
            derivedStateOf {
                if (routeLengthMeters <= 0.0) return@derivedStateOf null
                val idx = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return@derivedStateOf null
                pois.getOrNull(idx)?.distancesAlongRoute?.firstOrNull()
            }
        }

        RouteStrip(
            pois = pois,
            routeLengthMeters = routeLengthMeters,
            progressMeters = progressMeters,
            listPositionMeters = topVisibleMeters,
        )
        HorizontalDivider()

        // On entry mid-ride, jump to the first POI still ahead so "what's next" is the
        // first thing the rider sees. Fires once per screen entry (guarded by a saved
        // flag) so manual scrolling afterward is never yanked back. The live position
        // usually arrives after the (cached) POIs, so we gate on progress being known —
        // keying on that so the effect re-runs when the first stream value lands. pois
        // is pre-sorted by along-route distance (PoiQuery), so first-ahead is monotonic.
        val canScroll = progressMeters != null && pois.isNotEmpty() && routeLengthMeters > 0.0
        LaunchedEffect(canScroll, reentryKey) {
            if (didInitialScroll.value || !canScroll) return@LaunchedEffect
            val p = progressMeters ?: return@LaunchedEffect
            val firstAhead = pois.indexOfFirst { aheadMetersFor(it, p) != null }
            if (firstAhead > 0) listState.scrollToItem(firstAhead)
            didInitialScroll.value = true
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(pois, key = { it.id }) { poi ->
                val ahead = progressMeters?.let { aheadMetersFor(poi, it) }
                // Known progress but no ahead-crossing ⇒ behind the rider; how far back.
                val behind = progressMeters?.takeIf { ahead == null }?.let { behindMetersFor(poi, it) }
                PoiRow(
                    poi = poi,
                    hours = hoursOf(poi),
                    hasRoute = routeLengthMeters > 0,
                    aheadMeters = ahead,
                    behindMeters = behind,
                    onClick = { onOpenPoi(poi) },
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
    showBuild: Boolean,
    onBuild: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val building = buildState is BuildState.Building
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
                    painter = painterResource(R.drawable.ic_resupply),
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
            if (showStatusLine) BuildStatusLine(buildState)
        }
        // Build/rebuild: primary tint when there's work, muted after a build. While
        // building the icon just goes disabled — the single spinner lives in the
        // status line, so we never show two spinners at once. Hidden entirely when there's
        // nothing to build (empty + no route loaded), so we never offer an impossible build.
        if (showBuild) {
            IconButton(onClick = onBuild, enabled = !building) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Build",
                    tint = when {
                        building -> MaterialTheme.colorScheme.onSurfaceVariant
                        buildState is BuildState.Success -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

/**
 * The header's primary line, only used for transient build feedback: the current build
 * phase (with a spinner) and errors. The steady-state place count lives in the timeline
 * strip below, so idle/success leave this line blank — the logo and action icons carry
 * the header on their own.
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

        is BuildState.Error -> Text(
            state.message,
            style = MaterialTheme.typography.titleMedium,
            color = ClosedRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is BuildState.Success, is BuildState.Idle -> Unit
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
    onClick: () -> Unit,
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
        // the route distance to/from this POI, so both live on the narrow left edge and
        // the name/type text gets the full remaining width.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusIcon(style = style, hours = hours)
            DistanceLabel(aheadMeters = aheadMeters, behindMeters = behindMeters)
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
            // Detour (only meaningful along a route).
            if (hasRoute && poi.detourMeters > 0) {
                Text(
                    "detour ${formatDistance(poi.detourMeters)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        seasonal -> SeasonalGrey
        status?.state == OpeningHours.OpenState.OPEN -> OpenGreen
        status?.state == OpeningHours.OpenState.CLOSED -> ClosedRed
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
 * The no-places body. Two faces, chosen by whether a route is loaded — but both keep the
 * mark in the exact same slot/size so switching between them (and the build animation)
 * never jumps:
 *  - a route is loaded → [RouteReadyState]: the route's name + distance and a single
 *    "Find places" action (which animates the mark in place while building).
 *  - nothing loaded → [NoRouteState]: a calm explainer, no build affordance — you can't
 *    build a roadbook without a route, so we don't pretend you can.
 * While a build is running we always show the route-ready face (the mark is mid-animation),
 * regardless of the latest route signal, so the animation isn't yanked away.
 */
@Composable
private fun EmptyState(routeState: RouteState, buildState: BuildState, onBuild: () -> Unit) {
    val building = buildState is BuildState.Building
    val loaded = routeState as? RouteState.Loaded
    if (loaded != null || building) {
        RouteReadyState(loaded, buildState, onBuild)
    } else {
        NoRouteState()
    }
}

/**
 * Route loaded, nothing built yet: the route's name as the hero, its distance below, and a
 * single primary "Find places" action. While building, the same mark animates in place
 * (route traced, waypoints lighting up) and the copy switches to the live build phase — the
 * button stays in the layout (hidden + inert) so nothing shifts. [route] may be null only
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
                painter = painterResource(R.drawable.ic_resupply),
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
        Text(
            if (building) {
                "Tracing your route for cafés, water, shops and more…"
            } else {
                routeSubtitle(route)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        // The primary action: find places along the loaded route. Kept in the layout while
        // building (hidden + non-clickable) so the block's height stays constant.
        Button(
            onClick = onBuild,
            enabled = !building,
            modifier = Modifier.alpha(if (building) 0f else 1f),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Find places")
        }
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
 * No route loaded: a calm explainer with the static mark and no build button. Building a
 * roadbook needs a route to trace, so we guide the rider to load one rather than offering an
 * action that can't do what they'd expect. Same mark slot/size as [RouteReadyState] so the
 * two states cross-fade in place if the route signal flips.
 */
@Composable
private fun NoRouteState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_resupply),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Ready when you are",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Load a route on your Karoo and Resupply will find places along it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
