package io.resupply.karoo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

// Resupply brand colours (match the ic_resupply squircle).
private val Cream = Color(0xFFF2EDE3)
private val Tile = Color(0xFF0F1A18)
private val Contour = Color(0xFF2A3A36)
private val RouteOrange = Color(0xFFE8873B)
private val DotCyan = Color(0xFF5FC7E3)
private val DotYellow = Color(0xFFF2C14E)
private val DotPurple = Color(0xFFC79BE8)

// The animation lives in the same 512x512 space as ic_resupply: a dark squircle tile with
// faint contour lines, the "R" letterform, the stepped route and three waypoint dots. We
// describe the geometry here and scale it to the canvas at draw time. Drawing the full
// squircle (tile behind the R) keeps the letterform readable — matching the static icon.
private const val ART = 512f

// Squircle tile outline (rounded rect, corner radius 116), parsed once.
private val tilePath: Path = PathParser()
    .parsePathString(
        "M116,0 L396,0 A116,116 0 0 1 512,116 L512,396 " +
            "A116,116 0 0 1 396,512 L116,512 A116,116 0 0 1 0,396 " +
            "L0,116 A116,116 0 0 1 116,0 Z",
    )
    .toPath()

// Faint background contour curves (clipped to the tile).
private val contour1: Path = PathParser()
    .parsePathString("M-40 452 C 90 400 150 470 300 402 C 450 334 470 330 560 360")
    .toPath()
private val contour2: Path = PathParser()
    .parsePathString("M-40 512 C 90 460 150 530 300 462 C 450 394 470 390 560 420")
    .toPath()

// Route: the stepped polyline (same points as ic_resupply), traced start→end.
private val routePath = Path().apply {
    moveTo(52f, 404f)
    lineTo(152f, 404f)
    lineTo(228f, 282f)
    lineTo(332f, 282f)
    lineTo(398f, 152f)
    lineTo(462f, 152f)
}

// "R" letterform, parsed from the same path data as the drawable and pre-transformed by
// the source's translate(157,118) scale(1.054).
private val letterR: Path = PathParser()
    .parsePathString(
        "M0 0 H58 V260 H0 Z M58 0 H120 A68 68 0 0 1 120 136 H58 Z " +
            "M58 46 H120 A22 22 0 0 1 120 90 H58 Z M104 136 H164 L188 260 H128 Z",
    )
    .toPath()
    .apply {
        // The R's bowl is a counter (hole) — keep it open with even-odd winding, matching
        // ic_resupply's android:fillType="evenOdd". PathParser().toPath() defaults to
        // NonZero, which would fill the bowl solid.
        fillType = PathFillType.EvenOdd
        transform(
            Matrix().apply {
                translate(157f, 118f)
                scale(1.054f, 1.054f)
            },
        )
    }

// Each dot sits on a route vertex; the fraction is its position along the polyline (0..1),
// computed from the segment lengths. Cyan at (152,404), yellow at (332,282), purple at the
// terminus (462,152).
private data class Dot(val fraction: Float, val color: Color)
private val DOTS = listOf(
    Dot(0.179f, DotCyan),
    Dot(0.624f, DotYellow),
    Dot(1.0f, DotPurple),
)

/**
 * Animated Resupply loading squircle: the same icon as the static ic_resupply (dark tile,
 * R letterform, route + dots) with the stepped route traced by a bright travelling head
 * that loops from start to the final waypoint; each coloured dot pops as the head reaches
 * it. Because it draws the full squircle (tile behind the R), the static icon and this
 * animation read as one thing springing to life. Used as the centrepiece of the
 * build/"searching" state so the wait reads as the route being traced.
 */
@Composable
fun ResupplyLoadingLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val transition = rememberInfiniteTransition(label = "resupply-loading")
    // One full trace of the route per cycle, restarting cleanly at the end.
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )

    Canvas(modifier = modifier.size(size)) {
        drawResupply(progress)
    }
}

private fun DrawScope.drawResupply(progress: Float) {
    // Scale the 512-space geometry into the canvas (uniform, centred).
    val scale = min(this.size.width, this.size.height) / ART
    val dx = (this.size.width - ART * scale) / 2f
    val dy = (this.size.height - ART * scale) / 2f
    val toCanvas = Matrix().apply {
        translate(dx, dy)
        scale(scale, scale)
    }

    fun scaled(src: Path) = Path().apply {
        fillType = src.fillType
        addPath(src)
        transform(toCanvas)
    }

    // Squircle tile + faint contours (clipped to the tile), then the R on top.
    val tile = scaled(tilePath)
    drawPath(tile, color = Tile)
    clipPath(tile) {
        drawPath(scaled(contour1), color = Contour, alpha = 0.5f, style = Stroke(width = 8f * scale))
        drawPath(scaled(contour2), color = Contour, alpha = 0.5f, style = Stroke(width = 8f * scale))
    }
    drawPath(scaled(letterR), color = Cream)

    val path = scaled(routePath)
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    val casingWidth = 54f * scale
    val routeWidth = 22f * scale

    // Dark casing under the whole route (always full — this is the resting icon).
    drawPath(path, color = Tile, style = Stroke(width = casingWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // Base orange at low alpha; the traced portion rides brighter on top so a highlight
    // sweeps along the route.
    drawPath(path, color = RouteOrange, alpha = 0.5f, style = Stroke(width = routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

    val headLen = length * progress
    if (headLen > 0f) {
        val traced = Path()
        measure.getSegment(0f, headLen, traced, true)
        drawPath(traced, color = RouteOrange, style = Stroke(width = routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Waypoint dots: pop (with a short halo) as the head reaches each one, in brand colour.
    val dotOutline = 12f * scale
    val baseR = 26f * scale
    for (dot in DOTS) {
        val pos = measure.getPosition(length * dot.fraction)
        val since = progress - dot.fraction
        // Pop window: a brief scale-up right as the head arrives, settling to 1.0.
        val pop = if (since in 0f..0.12f) 1f + (0.12f - since) / 0.12f * 0.6f else 1f
        // Dots stay visible at rest (matching the static icon); reaching one brightens it.
        val alpha = if (progress >= dot.fraction) 1f else 0.85f
        // Soft halo on the freshly-reached dot.
        if (since in 0f..0.18f) {
            drawCircle(dot.color, radius = baseR * (1.8f + since * 2f), center = pos, alpha = 0.12f)
        }
        val r = baseR * pop
        drawCircle(dot.color, radius = r, center = pos, alpha = alpha)
        drawCircle(Tile, radius = r, center = pos, alpha = alpha, style = Stroke(width = dotOutline))
    }

    // Travelling head: a bright dot riding the tip of the traced route.
    if (progress > 0f && progress < 1f) {
        val head = measure.getPosition(headLen)
        drawCircle(Color.White, radius = routeWidth * 0.6f, center = head, alpha = 0.9f)
        drawCircle(RouteOrange, radius = routeWidth * 0.6f, center = head, alpha = 0.35f)
    }
}
