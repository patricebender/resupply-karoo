package io.resupply.karoo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Water face: a tap/faucet with a falling drip — reads as "refill here" more concretely
 * than a bare droplet. Shared by every water source (taps, fountains, springs, wells all
 * carry the REST_STOP type). Geometry kept as SVG pathData, nudged by the source group's
 * translate so it centres on the 24dp canvas; one white fill, so Compose's `Icon` tint
 * recolours it like any other [ImageVector].
 */
// Coordinates already carry the source drawable's <group translateX=1.5 translateY=0.25>
// baked in (absolute M/H/V offsets added), so the tap sits centred on the 24dp canvas.
private const val WATER_PATH =
    "M4.25 5.25h1.5a0.75 0.75 0 0 1 0.75 0.75v6a0.75 0.75 0 0 1 -0.75 0.75h-1.5a0.75 0.75 0 0 1 -0.75 -0.75v-6A0.75 0.75 0 0 1 4.25 5.25z" +
        "M7.25 6.75h8.25a4 4 0 0 1 4 4v3h-4v-2.25a0.75 0.75 0 0 0 -0.75 -0.75H7.25z" +
        "M10.25 3.75h2V6.75h-2z" +
        "M8.5 2h5.5a1 1 0 0 1 0 2H8.5a1 1 0 0 1 0 -2z" +
        "M17.5 14.85s-2.9 3.1 -2.9 5a2.9 2.9 0 0 0 5.8 0c0 -1.9 -2.9 -5 -2.9 -5z"

val WaterIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Water",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(WATER_PATH).toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()
}
