package io.resupply.karoo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A simple triangle-tent glyph for the campground category — Material has no clean tent
 * icon (Festival is a circus marquee, Cabin a hut), so we ship our own. A ridge-line tent
 * with a centre door slit, drawn as a filled path on the standard 24dp icon canvas so it
 * sits with the Material icons around it. `tint` recolours it like any [ImageVector].
 */
val TentIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Tent",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Outer tent triangle: apex at top-centre, base along the ground line, with a
        // V-notch cut up the middle for the door — so the fill reads as two flaps.
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 3f)      // apex
            lineTo(22f, 20f)     // down the right slope to the ground
            lineTo(14f, 20f)     // in along the ground to the door's right foot
            lineTo(12f, 14f)     // up to the door apex (centre)
            lineTo(10f, 20f)     // back down to the door's left foot
            lineTo(2f, 20f)      // out along the ground to the left corner
            close()              // up the left slope back to the apex
        }
    }.build()
}
