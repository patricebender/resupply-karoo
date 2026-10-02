package io.resupply.karoo.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.resupply.karoo.data.Poi
import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.haversine
import kotlin.math.abs

/**
 * Floor for the nearby radar's axis span (meters). Keeps a single very-near POI from stretching
 * the whole width — below this the scale stays fixed so "0.1 km" doesn't fill the radar edge to edge.
 */
private const val RADAR_MIN_SPAN_METERS = 500.0

/**
 * The route timeline: a thin horizontal track line for the route, with a colored dot per
 * POI at its position along the route. Each dot splays above or below the track by which
 * side of the route the POI is on and how far the detour is. The route's 0km/total endpoint
 * labels sit at the far corners of the bottom lane.
 *
 * Mid-ride it becomes a live "you are here": [progressMeters] places a bike glyph in the top
 * lane at the current position (with a guide line to the track) and dims passed POI dots.
 * [listStartMeters]/[listEndMeters] drive a range bracket in the bottom lane — an upward ⌈‾⌉
 * embracing the POIs of the currently visible list window, with one centered "a–b km" pill
 * reading the window's along-route span — so exploring the list and reading the timeline stay
 * in sync. All optional — without them the strip is the static build-time summary.
 *
 * **Nearby mode:** when there's no route ([routeLengthMeters] == 0) but a [riderLocation] is
 * given, the same band becomes a proximity "radar": the rider sits at the left edge, the
 * x-axis is straight-line distance out to [nearbyRadiusMeters], and each POI dot is placed by
 * its distance (splayed by category so same-distance dots don't collide). A "Live · N places"
 * pill shows the auto-refresh status where the header has no room. See [NearbyRadar].
 *
 * **Scrubbing:** when [onScrub] is given the strip becomes a draggable scrubber — press/drag
 * (or tap) anywhere along it reports the finger's position as a fraction `[0,1]` of the strip
 * width, which the host maps straight to a list index. Reporting a fraction (not meters) keeps
 * scrubbing responsive when POIs are few and far apart on a long route: each dot occupies an equal
 * slice of the width, so even finger travel scrolls evenly — no dead zones between distant dots.
 * The range bracket + pill track the scrolled window, and the pill grows drag chevrons while
 * scrubbing so the gesture reads at a glance.
 */
@Composable
fun RouteStrip(
    pois: List<Poi>,
    routeLengthMeters: Double,
    modifier: Modifier = Modifier,
    progressMeters: Double? = null,
    // Along-route km of the first/last visible list rows; drives the range bracket below the dots.
    listStartMeters: Double? = null,
    listEndMeters: Double? = null,
    // Nearby mode (routeLengthMeters == 0): rider position for the proximity radar.
    riderLocation: LatLng? = null,
    // Nearby mode: the detour radius, i.e. the radar's right-edge distance.
    nearbyRadiusMeters: Int = 0,
    // Favorited POI ids: their dots get a small yellow star above them. Route timeline only.
    favoritePoiIds: Set<String> = emptySet(),
    // Render the distance labels in miles/feet instead of km/m (resolved units mode).
    useImperial: Boolean = false,
    // Non-null → the strip is a scrubber: press/drag/tap reports the finger's position as a
    // fraction [0,1] of the strip width, which the host maps to a list index. Null → static.
    onScrub: ((Float) -> Unit)? = null,
) {
    // Nearby proximity radar takes over when there's no route but we have a rider fix. The visible
    // list window (listStart/EndMeters) is straight-line distance here, so the same range bracket
    // rides the radar as rides the route timeline.
    if (routeLengthMeters <= 0.0 && riderLocation != null) {
        NearbyRadar(
            pois, riderLocation, nearbyRadiusMeters,
            listStartMeters = listStartMeters, listEndMeters = listEndMeters,
            useImperial = useImperial,
            onScrub = onScrub,
            modifier = modifier,
        )
        return
    }
    // "You are here" must pop on both the light and dark ride themes and never blend
    // into a POI dot — a fixed high-chroma red, not the theme accent (which on some
    // themes matches a category color). It's a bike glyph riding in the top lane above the
    // dots, with a thin guide line dropping to the exact along-route position on the track.
    val riderColor = Color(0xFFE53935)
    val riderHalo = Color(0xFFFFFFFF)
    val favoriteColor = FavoriteYellow
    // The rider bike glyph, rasterized so it can be drawn INSIDE the Canvas (before the stars,
    // so a favorite star at the same x paints over the bike — both stay visible).
    val bikePainter = rememberVectorPainter(Icons.AutoMirrored.Filled.DirectionsBike)
    // Read theme colors here (composable scope) so the Canvas lambda can use them.
    val listMarkColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val hasRoute = routeLengthMeters > 0.0
    val progressFrac = progressMeters
        ?.takeIf { hasRoute }
        ?.let { (it / routeLengthMeters).coerceIn(0.0, 1.0).toFloat() }

    // The visible list window's start/end along the route, as fractions (drive the range bracket).
    val startFrac = listStartMeters
        ?.takeIf { hasRoute }
        ?.let { (it / routeLengthMeters).coerceIn(0.0, 1.0).toFloat() }
    val endFrac = listEndMeters
        ?.takeIf { hasRoute }
        ?.let { (it / routeLengthMeters).coerceIn(0.0, 1.0).toFloat() }

    // Scrub state: non-null while a finger is down on the strip (its axis fraction), which flips
    // the pill into its drag-affordance look. Null when not scrubbing. The host gets the position
    // in METERS (fraction * route length) via [onScrub], which it is free to leave null (e.g. a
    // single-POI strip) to keep the strip a static overview.
    var scrubFrac by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
    ) {
        // BoxWithConstraints so the floating pill can be positioned in dp by fraction —
        // the Canvas draws the track/dots/playhead, and the pill is a real Compose Text
        // overlaid on top (Canvas can't lay out text nicely).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fullWidth = maxWidth
            // Three compact lanes stacked: a top lane for the rider bike glyph + favorite stars
            // (so both ride above the dots and never touch them), the dot zone (route track +
            // splayed POI dots), then the bottom lane carrying an UPWARD bracket (⌈‾⌉) embracing
            // the visible window's dots, with the route's 0km/total endpoint labels at its far
            // corners and the window range pill centered on it.
            val starLaneH = 16.dp
            val dotZoneH = 34.dp
            // Bottom lane: bracket underline (top) + endpoint labels + range pill below it.
            val rangeLaneH = 22.dp
            // Range pill half-width used only as a fallback before its real width is measured.
            val pillWidth = 56.dp

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(starLaneH + dotZoneH + rangeLaneH)
                    // Scrubber: a down-move-up gesture maps local x → axis fraction → meters for
                    // the host, and parks [scrubFrac] for the pill affordance. The axis here spans
                    // the full width (left == 0), so the fraction is simply x / width. One gesture
                    // handles both tap and drag — the down already reports (a tap jumps), each move
                    // re-reports (a drag scrubs continuously), and the up clears the scrub state.
                    .then(if (onScrub != null && hasRoute) Modifier.scrubGesture(routeLengthMeters) { frac ->
                        scrubFrac = frac
                        frac?.let { onScrub(it) }
                    } else Modifier),
            ) {
                val left = 0f
                val right = size.width
                val laneTop = starLaneH.toPx()             // dot zone starts below the star lane
                val dotLaneY = laneTop + dotZoneH.toPx() / 2f
                val dotLaneBottom = laneTop + dotZoneH.toPx()  // dot zone / range lane boundary
                fun xAt(frac: Float) = left + frac * (right - left)

                if (!hasRoute) return@Canvas

                val riderX = progressFrac?.let { xAt(it) }

                // The route line itself: a thin horizontal track through the middle. The
                // dots splay off it by detour, so without this reference an off-center dot
                // has nothing to read "above/below the route" against.
                drawLine(
                    trackColor,
                    Offset(left, dotLaneY),
                    Offset(right, dotLaneY),
                    strokeWidth = 1.dp.toPx(),
                )

                // POI dots, offset off the track by which side of the route they're on
                // (left above, right below) AND how far the detour is: near-route POIs hug
                // the line, far ones splay to the lane edge. So vertical position carries
                // meaning, which de-clutters a dense route instead of smearing one row.
                // Normalize against the farthest detour on this route (with a floor so an
                // all-near route isn't exaggerated). Those behind the rider dim to "passed".
                val dotRadius = 4.dp.toPx()
                val minSplay = 3.dp.toPx()                 // even a near POI clears the track
                val maxSplay = dotZoneH.toPx() / 2f - dotRadius  // farthest sits at the lane edge
                val detourScale = maxOf(500, pois.maxOfOrNull { it.detourMeters } ?: 0).toFloat()
                // Favorite markers are collected here and drawn in a fixed lane at the very top
                // AFTER all dots — so a starred POI reads as "there's a favorite around here"
                // above the dot clutter, not lost inside a splayed cluster. (x, passed).
                val favoriteMarks = mutableListOf<Pair<Float, Boolean>>()
                for (poi in pois) {
                    val along = poi.distancesAlongRoute.firstOrNull() ?: continue
                    val frac = (along / routeLengthMeters).coerceIn(0.0, 1.0).toFloat()
                    val passed = progressFrac != null && frac < progressFrac
                    // side +1 (left) draws above; screen Y grows downward, so subtract.
                    val splay = if (poi.detourSide == 0) 0f
                        else minSplay + (maxSplay - minSplay) *
                            (poi.detourMeters / detourScale).coerceIn(0f, 1f)
                    val dotY = dotLaneY - poi.detourSide * splay
                    val dotX = xAt(frac)
                    drawCircle(
                        color = styleForType(poi.type).color.let { if (passed) it.copy(alpha = 0.3f) else it },
                        radius = dotRadius,
                        center = Offset(dotX, dotY),
                    )
                    if (poi.id in favoritePoiIds) favoriteMarks.add(dotX to passed)
                }

                // Rider "you are here": a bike glyph riding in the top lane at the current
                // along-route position, with a thin guide line dropping to the track so the
                // exact position reads against the dots. Drawn BEFORE the stars so a favorite
                // star at the same x paints over the bike — both stay visible.
                riderX?.let { rx ->
                    val bikeSize = 14.dp.toPx()
                    val bikeY = starLaneH.toPx() / 2f
                    // Faint guide line from under the bike down to the track.
                    drawLine(
                        riderColor.copy(alpha = 0.45f),
                        Offset(rx, starLaneH.toPx()),
                        Offset(rx, dotLaneY),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    // White halo disc behind the bike so it stays legible over dots/track.
                    drawCircle(riderHalo, radius = bikeSize * 0.62f, center = Offset(rx, bikeY))
                    // The bike glyph, tinted rider-red, centered on (rx, bikeY).
                    translate(left = rx - bikeSize / 2f, top = bikeY - bikeSize / 2f) {
                        with(bikePainter) {
                            draw(
                                size = Size(bikeSize, bikeSize),
                                colorFilter = ColorFilter.tint(riderColor),
                            )
                        }
                    }
                }

                // Favorite stars, in the dedicated top lane above the dots (so they never touch
                // a top-splayed POI), a touch bigger than a dot so they're legible in a dense
                // timeline. Drawn AFTER the bike so a star at the rider's x sits on top — both
                // the "you are here" bike and the favorite marker stay visible.
                if (favoriteMarks.isNotEmpty()) {
                    val starRadius = 5.5.dp.toPx()
                    val starY = starLaneH.toPx() / 2f        // centered in the top lane
                    for ((x, passed) in favoriteMarks) {
                        drawStar(
                            center = Offset(x, starY),
                            radius = starRadius,
                            color = favoriteColor.let { if (passed) it.copy(alpha = 0.3f) else it },
                        )
                    }
                }

                // The range bracket embracing the visible list window's POIs (⌈‾⌉ + tinted fill).
                // Shared with the nearby radar (see [drawRangeBracket]) so both strips read the same.
                if (startFrac != null && endFrac != null) {
                    drawRangeBracket(
                        startX = xAt(startFrac),
                        endX = xAt(endFrac),
                        dotRadius = dotRadius,
                        laneTop = laneTop,
                        dotLaneBottom = dotLaneBottom,
                        left = left,
                        right = right,
                        color = listMarkColor,
                    )
                }
            }

            // Bottom lane: the route's 0km / total endpoint labels pinned at the far corners,
            // and the window range pill centered on the bracket between them. Moving the
            // endpoints down here (from a former top lane) is what shrinks the strip's height.
            if (hasRoute) {
                val endLabel = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    if (useImperial) "0mi" else "0km",
                    style = MaterialTheme.typography.labelSmall,
                    color = endLabel,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
                Text(
                    formatDistance(routeLengthMeters, useImperial),
                    style = MaterialTheme.typography.labelSmall,
                    color = endLabel,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )

                // The range readout: a single "a–b km" pill centered on the bracket's window
                // midpoint — pill and bracket are ONE unit, so the pill always sits on the bracket
                // (they'd visibly desync if the pill chased the finger while the bracket tracked
                // the list). While a scrub is in flight it grows guillemet chevrons (‹ a–b km ›) as
                // a drag affordance; the chevrons fade out on release. Shared with the nearby radar.
                if (listStartMeters != null && listEndMeters != null &&
                    startFrac != null && endFrac != null
                ) {
                    RangePillOverlay(
                        text = formatRangeCompact(listStartMeters, listEndMeters),
                        centerFraction = (startFrac + endFrac) / 2f,
                        fullWidth = fullWidth,
                        scrubbing = scrubFrac != null,
                    )
                }
            }
        }
    }
}

/**
 * Turns the strip into a horizontal scrubber that coexists with the list's vertical scroll. [onScrub]
 * receives the finger's position as a fraction `[0,1]` of the *axis* — the width minus
 * [leftInset]/[rightInset], so the nearby radar (whose dots live inside those insets) and the
 * full-width route timeline both report over their own axis. A null arg means the gesture ended.
 *
 * Direction disambiguation is the whole point: a touch that starts on the strip must still be able
 * to scroll the LIST if the finger moves vertically — otherwise the strip "eats" every drag that
 * begins on it and the list feels stuck. So we don't consume the down; we wait for the touch slop
 * and only claim the gesture when it breaks HORIZONTALLY. A vertical break is left unconsumed so it
 * falls through to the LazyColumn. A tap (lift before slop) still jumps — it reports the down point.
 * [key] re-arms the handler when the axis scale changes (route length / radar radius).
 */
private fun Modifier.scrubGesture(
    key: Any?,
    leftInset: Dp = 0.dp,
    rightInset: Dp = 0.dp,
    onScrub: (Float?) -> Unit,
): Modifier = pointerInput(key) {
    val axisStart = leftInset.toPx()
    val axisSpan = (size.width - axisStart - rightInset.toPx()).coerceAtLeast(1f)
    fun report(x: Float) = onScrub(((x - axisStart) / axisSpan).coerceIn(0f, 1f))
    awaitEachGesture {
        // Don't consume the down — a vertical drag starting here must still reach the list.
        val down = awaitFirstDown(requireUnconsumed = false)
        // Resolve the gesture's intent at the touch slop: horizontal → scrub, vertical → let the
        // list scroll, lift-before-slop → a tap that jumps.
        var isHorizontal = false
        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, over ->
            if (abs(over.x) >= abs(over.y)) {
                isHorizontal = true
                change.consume()   // claim it as a scrub so the list won't also scroll
            }
            // Vertical: leave unconsumed; we bail below and the list takes over.
        }
        if (slopChange == null) {
            // Lifted before crossing slop → a tap. Jump to the tapped point, then clear.
            report(down.position.x)
            onScrub(null)
            return@awaitEachGesture
        }
        if (!isHorizontal) return@awaitEachGesture   // vertical drag → hand off to the list
        // Claimed a horizontal scrub: seed at the slop position, then follow the finger, consuming
        // each move so the list stays put, until the last pointer lifts.
        report(slopChange.position.x)
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { change ->
                report(change.position.x)
                change.consume()
            }
        } while (event.changes.any { it.pressed })
        onScrub(null)
    }
}

/**
 * The centered range pill positioned on the bracket's window midpoint. [centerFraction] is the
 * midpoint as a fraction of [fullWidth] (each strip computes it consistent with its own axis), and
 * the pill is clamped by its MEASURED width so a wide label near an edge pins flush inside rather
 * than spilling past. Pinned to the bottom lane. Shared by the route timeline and the nearby radar.
 */
@Composable
private fun BoxScope.RangePillOverlay(
    text: String,
    centerFraction: Float,
    fullWidth: Dp,
    // While true the pill wears guillemet chevrons (‹ … ›) as a "drag me" affordance and lifts
    // slightly. The chevrons fade in/out so they vanish cleanly on release.
    scrubbing: Boolean = false,
) {
    var pillW by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val cx = fullWidth * centerFraction
    val pillX = (cx - pillW / 2).coerceIn(0.dp, (fullWidth - pillW).coerceAtLeast(0.dp))
    RangePill(
        text = text,
        scrubbing = scrubbing,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .offset(x = pillX)
            .onSizeChanged { pillW = with(density) { it.width.toDp() } },
    )
}

/**
 * A rounded primary pill carrying a bold km readout — the shared style for the range pills. While
 * [scrubbing] it flanks the readout with guillemet chevrons that slide out + fade in (‹ a–b km ›),
 * reading as "drag to move"; on release they retract and fade so the pill returns to its calm
 * resting form. The chevron gap is animated (not toggled) so the text doesn't jump.
 */
@Composable
private fun RangePill(text: String, modifier: Modifier = Modifier, scrubbing: Boolean = false) {
    val chevronAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (scrubbing) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "scrubChevronAlpha",
    )
    val chevronGap by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (scrubbing) 5.dp else 0.dp,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "scrubChevronGap",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading chevron: present only while scrubbing (zero-width + transparent at rest, so the
        // resting pill is unchanged). alpha carries the fade; the gap Spacer carries the slide.
        if (chevronAlpha > 0f) {
            Text(
                "‹",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = chevronAlpha),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.width(chevronGap))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (chevronAlpha > 0f) {
            Spacer(Modifier.width(chevronGap))
            Text(
                "›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = chevronAlpha),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Draws the visible-list-window range bracket: a soft tinted fill spanning the dot zone with a
 * ⌈‾⌉ clamp underlining it, embracing the dots between [startX] and [endX] (the along-axis
 * positions of the first and last visible rows). Extends to the OUTER edge of those dots (±
 * [dotRadius]) and enforces a minimum on-screen width so a single-POI window still reads as a
 * bracket, not a hairline. Shared by the route timeline and the nearby radar so the "where the
 * list is looking" cue is identical in both — only the axis feeding [startX]/[endX] differs.
 */
private fun DrawScope.drawRangeBracket(
    startX: Float,
    endX: Float,
    dotRadius: Float,
    laneTop: Float,
    dotLaneBottom: Float,
    left: Float,
    right: Float,
    color: Color,
) {
    val barY = dotLaneBottom + 2.dp.toPx()   // underline just under the dots
    val rise = 6.dp.toPx()                   // how far end ticks rise above the bar
    val stroke = 2.5.dp.toPx()

    // Extend to the OUTER edges of the first/last dots so the bracket embraces the whole first
    // and last POI rather than cutting through their centers.
    val rawSx = startX - dotRadius
    val rawEx = endX + dotRadius
    // Enforce a minimum on-screen width so a tiny window — or a single POI, where start == end —
    // still draws a proper ⌈‾⌉ bracket, not a hairline.
    val minW = 18.dp.toPx()
    val mid = (rawSx + rawEx) / 2f
    val half = maxOf((rawEx - rawSx) / 2f, minW / 2f)
    val sx = (mid - half).coerceAtLeast(left)
    val ex = (mid + half).coerceAtMost(right)

    // Soft tinted fill spanning the full dot zone → POIs in the window sit inside the highlight,
    // not beside it. Rounded top corners so it tucks under the bracket cleanly.
    val corner = CornerRadius(3.dp.toPx())
    drawPath(
        Path().apply {
            addRoundRect(
                RoundRect(
                    left = sx, top = laneTop, right = ex, bottom = barY,
                    topLeftCornerRadius = corner, topRightCornerRadius = corner,
                    bottomLeftCornerRadius = CornerRadius.Zero,
                    bottomRightCornerRadius = CornerRadius.Zero,
                )
            )
        },
        color = color.copy(alpha = 0.14f),
    )
    // The ⌈‾⌉ clamp as ONE continuous path (rise → across → rise) with round joins/caps, so the
    // corners are smooth instead of three lines butting together at ragged right angles.
    val bracket = Path().apply {
        moveTo(sx, barY - rise)
        lineTo(sx, barY)
        lineTo(ex, barY)
        lineTo(ex, barY - rise)
    }
    drawPath(
        bracket,
        color = color,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * A filled 5-point star centered at [center] with outer [radius], marking a favorited POI above
 * its timeline dot. Built as a 10-vertex path alternating outer/inner points (inner radius at
 * ~0.42 of outer for the classic star waist). First point aimed straight up so it reads upright.
 */
private fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val innerRadius = radius * 0.42f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else innerRadius
        // Start at -90° (straight up) and step 36° per vertex.
        val angle = Math.toRadians((-90 + i * 36).toDouble())
        val px = center.x + (r * Math.cos(angle)).toFloat()
        val py = center.y + (r * Math.sin(angle)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color)
}

/**
 * Proximity "radar" — the RouteStrip band repurposed for a nearby (no-route) build. The rider
 * sits at the left edge; the x-axis is straight-line distance from them, out to
 * [radiusMeters] at the right. Each POI is a dot at its distance, splayed above/below the
 * baseline by category so same-distance dots of different types don't collide (there's no
 * "detour side" without a route). The auto-refresh "Live" status lives in the header chip, so
 * the band stays uncluttered. Reuses the same lane geometry, dot radius, and category colours
 * as the route timeline so the two read as one component.
 */
@Composable
private fun NearbyRadar(
    pois: List<Poi>,
    rider: LatLng,
    radiusMeters: Int,
    // Straight-line distance (m) of the first/last visible list rows — drives the SAME range
    // bracket the route timeline uses, over this radar's distance axis. Null → no bracket.
    listStartMeters: Double?,
    listEndMeters: Double?,
    useImperial: Boolean = false,
    // Non-null → scrubbable: reports the finger's position as a fraction [0,1] of the strip width
    // (the host maps it to a list index). Null → static radar.
    onScrub: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val riderColor = Color(0xFF2E7D32)   // green — the rider anchor (bike glyph)
    val bracketColor = MaterialTheme.colorScheme.primary

    // Distance to each POI, so a dot's x tracks the rider live as they move.
    val distances = pois.map { haversine(rider, LatLng(it.lat, it.lng)) }

    // Scale the axis to the FURTHEST POI actually found, not the theoretical search radius. Smart
    // search reaches out to ~2 km but usually finds everything within a few hundred metres, which
    // otherwise leaves most of the radar empty (dots crammed at the left). A small floor keeps a
    // single very-near POI from filling the whole width, and we pad ~15% past the last dot so its
    // label/right edge isn't clipped. With no POIs, fall back to the radius so the scale still reads.
    val farthest = distances.filter { it <= radiusMeters }.maxOrNull()
    val radius = when {
        farthest != null -> maxOf(farthest * 1.15, RADAR_MIN_SPAN_METERS)
        else -> radiusMeters.coerceAtLeast(1).toDouble()
    }

    // The visible list window as fractions of this radar's distance axis (0 at the rider, 1 at
    // [radius]) — feeds the shared range bracket, exactly like the route strip's start/endFrac.
    val startFrac = listStartMeters?.let { (it / radius).coerceIn(0.0, 1.0).toFloat() }
    val endFrac = listEndMeters?.let { (it / radius).coerceIn(0.0, 1.0).toFloat() }

    // Scrub state, mirroring the route timeline: the finger's axis fraction while dragging (drives
    // the pill affordance), null otherwise. The host gets it as meters (fraction * radius).
    var scrubFrac by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fullWidth = maxWidth
            val topLaneH = 4.dp     // small breathing room above the dots (matches route strip feel)
            val dotZoneH = 34.dp
            // Bottom lane: bracket underline + scale label + range pill (mirrors the route strip).
            val rangeLaneH = 22.dp
            // Insets so dots aren't clipped and clear the bike anchor at the left. Declared here so
            // the pill's center fraction maps through the SAME axis as the Canvas dots/bracket.
            val leftInset = 24.dp
            val rightInset = 6.dp

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topLaneH + dotZoneH + rangeLaneH)
                    // Scrubber over the radar's inset distance axis (0 at the rider anchor, 1 at
                    // the right edge). The gesture reports the fraction; we hand the host meters.
                    .then(if (onScrub != null) Modifier.scrubGesture(radius, leftInset, rightInset) { frac ->
                        scrubFrac = frac
                        frac?.let { onScrub(it) }
                    } else Modifier),
            ) {
                val left = 0f
                val right = size.width
                val laneTop = topLaneH.toPx()
                val baselineY = laneTop + dotZoneH.toPx() / 2f
                val dotLaneBottom = laneTop + dotZoneH.toPx()
                val leftInsetPx = leftInset.toPx()
                val rightInsetPx = rightInset.toPx()
                fun xAt(frac: Float) = left + leftInsetPx + frac * (right - left - leftInsetPx - rightInsetPx)

                // Baseline the dots read against. Starts at the dot axis origin (past the bike
                // anchor at the left edge) so the line doesn't cut across the glyph.
                drawLine(
                    baselineColor,
                    Offset(left + leftInsetPx, baselineY),
                    Offset(right, baselineY),
                    strokeWidth = 1.dp.toPx(),
                )

                // Category splay: assign each category a lane above/below the baseline so
                // same-distance dots of different types don't overlap. Alternate up/down by
                // category index; magnitude grows with lane count but stays within the zone.
                val dotRadius = 4.dp.toPx()
                val maxSplay = dotZoneH.toPx() / 2f - dotRadius
                val cats = pois.mapNotNull { io.resupply.karoo.data.Category.ofType(it.type) }
                    .distinct()
                fun splayFor(catIndex: Int): Float {
                    if (catIndex < 0) return 0f
                    // 0→0, 1→+, 2→−, 3→+2, 4→−2 … so lanes fan out symmetrically.
                    val step = (catIndex + 1) / 2
                    val dir = if (catIndex % 2 == 1) 1 else -1
                    val lanes = (cats.size + 1) / 2
                    val unit = if (lanes == 0) 0f else maxSplay / lanes
                    return (dir * step * unit).coerceIn(-maxSplay, maxSplay)
                }

                for ((i, poi) in pois.withIndex()) {
                    val d = distances[i]
                    if (d > radius) continue
                    val frac = (d / radius).coerceIn(0.0, 1.0).toFloat()
                    val catIndex = io.resupply.karoo.data.Category.ofType(poi.type)
                        ?.let { cats.indexOf(it) } ?: -1
                    val dotY = baselineY - splayFor(catIndex)
                    drawCircle(
                        color = styleForType(poi.type).color,
                        radius = dotRadius,
                        center = Offset(xAt(frac), dotY),
                    )
                }

                // The SAME range bracket the route timeline draws, over the distance axis — so
                // scrolling the list highlights the corresponding proximity band on the radar.
                if (startFrac != null && endFrac != null) {
                    drawRangeBracket(
                        startX = xAt(startFrac),
                        endX = xAt(endFrac),
                        dotRadius = dotRadius,
                        laneTop = laneTop,
                        dotLaneBottom = dotLaneBottom,
                        left = left + leftInsetPx,
                        right = right,
                        color = bracketColor,
                    )
                }
            }

            // Rider anchor: a small bike glyph pinned to the left edge, on the baseline. The
            // x-axis is distance FROM the rider, so "you" are always at 0 (the left) — the
            // glyph makes that legible without implying motion (the POI dots are what move as
            // the rider rides). Vertically centered on the baseline (top lane + half dot zone).
            Icon(
                imageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                contentDescription = "You",
                tint = riderColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = topLaneH + dotZoneH / 2 - 9.dp) // center 18dp icon on baseline
                    .size(18.dp),
            )

            // Right-edge scale label = the axis max. Pinned to the bottom lane (like the route
            // strip's endpoint labels). Left is the rider (0), shown by the bike.
            Text(
                formatDistance(radius, useImperial),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd),
            )

            // The window range pill, centered on the bracket midpoint — the shared overlay, mapped
            // through this radar's inset axis so it sits over the bracket, not the raw fraction.
            if (listStartMeters != null && listEndMeters != null &&
                startFrac != null && endFrac != null
            ) {
                val density = LocalDensity.current
                val axisStart = with(density) { leftInset.toPx() }
                val axisSpan = with(density) { (fullWidth - leftInset - rightInset).toPx() }
                val fullPx = with(density) { fullWidth.toPx() }
                // Pill sits on the bracket's window midpoint (pill + bracket are one unit, so they
                // never desync). Map the axis fraction to a fraction of the FULL width (the overlay
                // positions in full-width terms), accounting for the radar's left/right insets.
                val midFrac = (startFrac + endFrac) / 2f
                val centerFraction = (axisStart + midFrac * axisSpan) / fullPx
                RangePillOverlay(
                    text = formatRangeCompact(listStartMeters, listEndMeters),
                    centerFraction = centerFraction,
                    fullWidth = fullWidth,
                    scrubbing = scrubFrac != null,
                )
            }
            // The "Live" status now lives in the header (a chip replacing the rebuild button),
            // so the band stays uncluttered: bike anchor, POI dots, bracket, and the scale.
        }
    }
}
