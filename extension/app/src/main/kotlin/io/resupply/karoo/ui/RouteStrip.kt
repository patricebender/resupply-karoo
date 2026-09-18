package io.resupply.karoo.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.resupply.karoo.data.Poi
import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.haversine

/**
 * The route timeline: a thin horizontal track line for the route, with a colored dot per
 * POI at its position along the route. Each dot splays above or below the track by which
 * side of the route the POI is on and how far the detour is. Start/total labels sit in the
 * top lane.
 *
 * Mid-ride it becomes a live "you are here": [progressMeters] places a red rider
 * playhead at the current position and dims passed POI dots. [listPositionMeters] drives
 * a floating km pill (with a bracket line) that slides along the strip to show where the
 * scrolled list currently sits, so exploring the list and reading the timeline stay in
 * sync. Both are optional — without them the strip is the static build-time summary.
 *
 * **Nearby mode:** when there's no route ([routeLengthMeters] == 0) but a [riderLocation] is
 * given, the same band becomes a proximity "radar": the rider sits at the left edge, the
 * x-axis is straight-line distance out to [nearbyRadiusMeters], and each POI dot is placed by
 * its distance (splayed by category so same-distance dots don't collide). A "Live · N places"
 * pill shows the auto-refresh status where the header has no room. See [NearbyRadar].
 */
@Composable
fun RouteStrip(
    pois: List<Poi>,
    routeLengthMeters: Double,
    modifier: Modifier = Modifier,
    progressMeters: Double? = null,
    // Along-route position of the top of the list; drives the floating km pill + bracket.
    listPositionMeters: Double? = null,
    // Nearby mode (routeLengthMeters == 0): rider position for the proximity radar.
    riderLocation: LatLng? = null,
    // Nearby mode: the detour radius, i.e. the radar's right-edge distance.
    nearbyRadiusMeters: Int = 0,
) {
    // Nearby proximity radar takes over when there's no route but we have a rider fix.
    if (routeLengthMeters <= 0.0 && riderLocation != null) {
        NearbyRadar(pois, riderLocation, nearbyRadiusMeters, modifier)
        return
    }
    // "You are here" must pop on both the light and dark ride themes and never blend
    // into a POI dot — a fixed high-chroma red, not the theme accent (which on some
    // themes matches a category color). The marker is a vertical playhead that cuts
    // THROUGH the dot lane, so a cluster of POIs at the rider's position can't occlude it.
    val riderColor = Color(0xFFE53935)
    val riderHalo = Color(0xFFFFFFFF)
    // Read theme colors here (composable scope) so the Canvas lambda can use them.
    val listMarkColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val hasRoute = routeLengthMeters > 0.0
    val progressFrac = progressMeters
        ?.takeIf { hasRoute }
        ?.let { (it / routeLengthMeters).coerceIn(0.0, 1.0).toFloat() }

    // The list's current position along the route (top-of-list row), as a fraction.
    val listFrac = listPositionMeters
        ?.takeIf { hasRoute }
        ?.let { (it / routeLengthMeters).coerceIn(0.0, 1.0).toFloat() }

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
            // A floating rounded pill sits in a lane ABOVE the dot lane, showing the
            // list's current km, with a bracket dropping onto the dot lane. A thin
            // horizontal track line marks the route; POI dots splay off it by detour side.
            val pillLaneH = 18.dp
            // Tall enough that a max-detour dot splayed to the lane edge stays clear of the
            // pill lane above and the km labels below (half-lane carries splay + dot radius).
            val dotZoneH = 34.dp

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pillLaneH + dotZoneH),
            ) {
                val left = 0f
                val right = size.width
                val laneTop = pillLaneH.toPx()             // dot zone starts below the pill lane
                val dotLaneY = laneTop + dotZoneH.toPx() / 2f
                val laneBottom = size.height
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
                for (poi in pois) {
                    val along = poi.distancesAlongRoute.firstOrNull() ?: continue
                    val frac = (along / routeLengthMeters).coerceIn(0.0, 1.0).toFloat()
                    val passed = progressFrac != null && frac < progressFrac
                    // side +1 (left) draws above; screen Y grows downward, so subtract.
                    val splay = if (poi.detourSide == 0) 0f
                        else minSplay + (maxSplay - minSplay) *
                            (poi.detourMeters / detourScale).coerceIn(0f, 1f)
                    val dotY = dotLaneY - poi.detourSide * splay
                    drawCircle(
                        color = styleForType(poi.type).color.let { if (passed) it.copy(alpha = 0.3f) else it },
                        radius = dotRadius,
                        center = Offset(xAt(frac), dotY),
                    )
                }

                // The list-position bracket: a thin vertical dropping from the pill lane
                // through the dot lane, marking where the list currently sits.
                listFrac?.let { f ->
                    val cx = xAt(f)
                    drawLine(
                        listMarkColor,
                        Offset(cx, laneTop),
                        Offset(cx, laneBottom),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }

                // Rider playhead: a red vertical line through the dot lane, capped with a
                // triangle pointer. Cuts through any dot cluster at the same x.
                riderX?.let { drawRiderMarker(it, laneTop, laneBottom, riderColor, riderHalo) }
            }

            // Endpoint labels live IN the top lane (not a row below): 0km pinned left,
            // total km pinned right. The current-position pill floats between them and
            // slides as the list scrolls. As the pill nears an end, that endpoint label
            // fades out so the moving pill gracefully "merges" into the endpoint instead
            // of colliding — one lane carries all three readouts.
            if (hasRoute) {
                val endLabel = MaterialTheme.colorScheme.onSurfaceVariant
                // How close (as a fraction of width) the pill can get before an endpoint
                // label starts fading, reaching 0 alpha when the pill sits on the end.
                val fadeStart = 0.16f
                val f = listFrac ?: -1f
                val startAlpha = if (f < 0f) 1f else ((f / fadeStart).coerceIn(0f, 1f))
                val endAlpha = if (f < 0f) 1f else (((1f - f) / fadeStart).coerceIn(0f, 1f))

                Text(
                    "0km",
                    style = MaterialTheme.typography.labelSmall,
                    color = endLabel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .alpha(startAlpha),
                )
                Text(
                    formatDistance(routeLengthMeters),
                    style = MaterialTheme.typography.labelSmall,
                    color = endLabel,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .alpha(endAlpha),
                )

                // The floating current-position pill, centered on the list fraction and
                // clamped so it never spills past the edges.
                if (listFrac != null && listPositionMeters != null) {
                    val pillWidth = 52.dp
                    val cx = fullWidth * listFrac
                    val pillX = (cx - pillWidth / 2)
                        .coerceIn(0.dp, (fullWidth - pillWidth).coerceAtLeast(0.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = pillX)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    ) {
                        Text(
                            formatDistance(listPositionMeters),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The "you are here" rider playhead: a vertical red line from the strip top [topY] down
 * to [bottomY], capped by a downward triangle pointer at the top. Because it's a
 * vertical bar (not a point), a cluster of dots at the rider's along-route position
 * can't occlude it — it cuts straight through. A light [halo] underlay keeps it legible
 * on both ride themes and over the dots.
 */
private fun DrawScope.drawRiderMarker(x: Float, topY: Float, bottomY: Float, color: Color, halo: Color) {
    val stem = 3.dp.toPx()
    val head = 6.dp.toPx() // half-width of the triangle pointer

    // Halo underlay: a fatter light line + outline so the red never disappears.
    drawLine(halo, Offset(x, topY), Offset(x, bottomY), strokeWidth = stem + 3.dp.toPx())

    // Triangle pointer at the very top, aimed down the playhead.
    val tri = Path().apply {
        moveTo(x - head, topY)
        lineTo(x + head, topY)
        lineTo(x, topY + head * 1.4f)
        close()
    }
    drawPath(tri, halo, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
    drawPath(tri, color)

    // The bright red playhead.
    drawLine(color, Offset(x, topY), Offset(x, bottomY), strokeWidth = stem)
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
    modifier: Modifier = Modifier,
) {
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val riderColor = Color(0xFF2E7D32)   // green — the rider anchor (bike glyph)
    // Radar spans out to the detour radius; guard against a zero so we never divide by it.
    val radius = radiusMeters.coerceAtLeast(1).toDouble()

    // Distance to each POI, so a dot's x tracks the rider live as they move.
    val distances = pois.map { haversine(rider, LatLng(it.lat, it.lng)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fullWidth = maxWidth
            val pillLaneH = 18.dp
            val dotZoneH = 34.dp

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pillLaneH + dotZoneH),
            ) {
                val left = 0f
                val right = size.width
                val laneTop = pillLaneH.toPx()
                val baselineY = laneTop + dotZoneH.toPx() / 2f
                // Insets so dots aren't clipped at the edges. The left is wider to clear the
                // bike anchor glyph (~18dp) sitting at x=0 — a near-rider POI dot then starts
                // just past the bike rather than under it. Right inset just avoids edge clip.
                val leftInset = 24.dp.toPx()
                val rightInset = 6.dp.toPx()
                fun xAt(frac: Float) = left + leftInset + frac * (right - left - leftInset - rightInset)

                // Baseline the dots read against. Starts at the dot axis origin (past the bike
                // anchor at the left edge) so the line doesn't cut across the glyph.
                drawLine(
                    baselineColor,
                    Offset(left + leftInset, baselineY),
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
                // (The rider anchor is a bike glyph overlaid at the left edge — see below —
                // rather than a Canvas playhead: it marks the origin of the distance axis,
                // "you", not a moving position, so it shouldn't borrow the route strip's
                // moving-playhead language.)
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
                    .offset(y = pillLaneH + dotZoneH / 2 - 9.dp) // center 18dp icon on baseline
                    .size(18.dp),
            )

            // Right-edge scale label = the radius. Left is the rider (0), shown by the bike.
            Text(
                formatDistance(radius),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            // The "Live" status now lives in the header (a chip replacing the rebuild button),
            // so the band stays uncluttered: just the bike anchor, POI dots, and the scale.
        }
    }
}
