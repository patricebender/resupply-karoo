package io.resupply.karoo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 */
@Composable
fun RouteStrip(
    pois: List<Poi>,
    routeLengthMeters: Double,
    modifier: Modifier = Modifier,
    progressMeters: Double? = null,
    // Along-route position of the top of the list; drives the floating km pill + bracket.
    listPositionMeters: Double? = null,
) {
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
