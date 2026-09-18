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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import io.resupply.karoo.data.OpeningHours
import io.resupply.karoo.data.Poi
import io.resupply.karoo.data.PoiSource
import io.resupply.karoo.data.aheadMetersFor
import io.resupply.karoo.data.behindMetersFor
import io.resupply.karoo.data.RouteState
import io.resupply.karoo.data.formatKm
import io.resupply.karoo.data.poiSourceFor
import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.haversine

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
    routeLengthMeters: Double,
    // Live along-route position of the rider, or null when there's no route/live
    // stream. Drives the timeline marker, per-row distance-ahead, and the initial
    // scroll to the first POI ahead.
    progressMeters: Double?,
    // Whether a route is loaded on the Karoo (and its name/distance). Drives the empty
    // state: route hero + "Find places" when loaded, a "find places near you" prompt when not.
    routeState: RouteState,
    // Rider location, for a nearby build's straight-line list distances. Null when unknown.
    riderLocation: LatLng?,
    // Nearby POIs are being auto-refreshed as the rider moves → the proximity band shows a
    // "Live" indicator.
    nearbyLive: Boolean,
    // The detour radius (meters) — sets the nearby proximity band's right-edge scale.
    nearbyRadiusMeters: Int,
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
    // Show only the enabled categories. The build keeps every category in memory, so this is
    // the sole gate on what the overview renders — re-enabling a category surfaces it instantly.
    // All downstream logic (source derivation, strip, radar, list, auto-scroll) operates on this
    // filtered set. We keep the raw [pois] to tell "nothing built" apart from "built, but every
    // category is toggled off" (below) — the two want different empty faces.
    val visiblePois = remember(pois, enabledCategories) {
        pois.filter { Category.ofType(it.type) in enabledCategories }
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
            onOpenSettings = onOpenSettings,
        )
        HorizontalDivider()

        // A roadbook exists but every category is toggled off → don't dangle the "Find places"
        // build prompt (a rebuild would change nothing — the POIs are already in memory, just
        // filtered out). Show a distinct "no categories selected" face that points at Settings.
        if (pois.isNotEmpty() && visiblePois.isEmpty()) {
            NoCategoriesState(onOpenSettings)
            return@Column
        }

        if (pois.isEmpty()) {
            // No places yet: a route is loaded (its hero + "Find places"), or nothing is (a
            // "find places near you" prompt with a nearby build button). Both keep the mark in
            // the same slot so a build animates in place without a jump.
            EmptyState(routeState, buildState, onBuild)
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
                    listPositionMeters = topVisibleMeters,
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
                    )
                    HorizontalDivider()
                }
            }
        }

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
    nearbyLive: Boolean,
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
            // Transient build feedback only (the spinner).
            if (showStatusLine && buildState is BuildState.Building) BuildStatusLine(buildState)
        }
        // Live chip: nearby POIs auto-refresh as the rider moves, so there's no manual rebuild
        // to offer — the chip replaces the old rebuild button, signalling "tracking you". A
        // pulsing dot carries the motion; the header (unlike the band) has room for it beside
        // the settings gear. Manual rebuild lives in Settings for the route case.
        if (nearbyLive) LiveChip()
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

/**
 * A slow-breathing green dot — the shared "live" motif. Used in the header [LiveChip] and the
 * enable-live invitation, so both read as the same feature. Confined to the dot so motion
 * stays subtle on a bike computer glanced at speed.
 */
@Composable
private fun PulsingDot(size: Dp, color: Color = OpenGreen) {
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
            color = OpenGreen,
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
            painter = painterResource(R.drawable.ic_resupply),
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
private fun EmptyState(routeState: RouteState, buildState: BuildState, onBuild: () -> Unit) {
    val loaded = routeState as? RouteState.Loaded
    // Pick the face by whether a ROUTE is loaded, not by whether a build is running: a nearby
    // build (no route) must stay on the nearby face and morph in place, or the button would
    // vanish and be replaced by the route-oriented "Tracing your route…" copy — an abrupt swap
    // between two different layouts. Each face animates its own mark while building.
    if (loaded != null) {
        RouteReadyState(loaded, buildState, onBuild)
    } else {
        NearbyReadyState(buildState, onBuild)
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
 * No route loaded: a "find places near you" prompt with the [FindLiveResupplyButton] hero. A
 * build with no route falls back to POIs around the rider, so we offer (and run) that build
 * right here — the same face morphs into a building state rather than being replaced: the mark
 * animates in its slot, the title becomes the live build phase, and the button crossfades to a
 * spinner + phase line so nothing hard-disappears. Same 72.dp mark slot as [RouteReadyState].
 */
@Composable
private fun NearbyReadyState(buildState: BuildState, onBuild: () -> Unit) {
    val building = buildState as? BuildState.Building
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same slot/size as the idle mark — only the renderer changes when a build starts, so the
        // static squircle springs to life in place rather than being swapped out.
        if (building != null) {
            ResupplyLoadingLogo(size = 72.dp)
        } else {
            Image(
                painter = painterResource(R.drawable.ic_resupply),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        // While building, the title carries the live phase ("Searching…", "Waiting for GPS…").
        Text(
            building?.phase ?: "No route loaded",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        // The CTA IS the "enable live" affordance and the hero of the screen: a near-black pill
        // echoing the logo tile above, with the pulsing green "live" motif carried inline so the
        // button previews the header chip it lights up. While building it stays put but dims and
        // goes inert — the title above carries the progress.
        FindLiveResupplyButton(onClick = onBuild, enabled = building == null)
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
