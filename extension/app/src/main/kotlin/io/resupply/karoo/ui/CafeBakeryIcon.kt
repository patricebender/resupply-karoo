package io.resupply.karoo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Café & Bakery face: a coffee cup paired with a segmented croissant — so the category
 * reads as cafés *and* bakeries, not just coffee. Material ships neither shape, so this is a
 * bespoke glyph. The geometry is kept as a single SVG pathData string (cup, then the
 * croissant's fan of wedges) and parsed into the vector at build time; it's one white fill
 * with even-odd rule (the cup handle and wedge overlaps punch through) so Compose's `Icon`
 * tint recolours the whole thing like any other [ImageVector]. Standard 24dp canvas.
 */
private const val CAFE_BAKERY_PATH =
    "M6.08 14.20 6.17 14.18 6.24 14.13 6.63 13.67 7.00 13.30 7.45 12.88 7.88 12.53 8.32 12.21 " +
        "8.80 11.90 9.67 11.42 10.19 11.19 10.73 10.98 11.36 10.79 11.91 10.65 13.00 10.46 " +
        "13.51 10.41 14.30 10.38 14.75 10.14 15.18 9.81 15.57 9.38 15.87 8.90 16.06 8.37 " +
        "16.14 7.82 16.12 7.27 15.98 6.73 15.74 6.23 15.40 5.77 15.15 5.53 14.91 5.33 " +
        "14.61 5.14 14.33 4.99 14.03 4.87 13.68 4.77 13.04 4.67 13.04 4.23 12.99 4.05 " +
        "12.92 3.95 12.83 3.86 12.72 3.80 12.60 3.77 1.50 3.77 1.32 3.80 1.17 3.89 1.05 4.06 " +
        "1.00 4.25 1.00 10.29 1.04 10.72 1.11 11.12 1.23 11.53 1.39 11.91 1.73 12.49 " +
        "2.17 13.02 2.67 13.44 3.25 13.79 3.84 14.03 4.43 14.16 4.91 14.20Z" +
        "M13.04 6.39 13.31 6.43 13.57 6.53 13.81 6.66 14.00 6.81 14.17 7.01 14.29 7.21 " +
        "14.37 7.44 14.39 7.68 14.37 7.90 14.30 8.13 14.17 8.35 14.00 8.54 13.81 8.70 " +
        "13.57 8.83 13.31 8.92 13.04 8.97Z" +
        "M12.86 11.59 12.72 11.65 12.61 11.76 12.55 11.89 12.54 12.04 13.70 18.60 13.79 18.78 " +
        "13.94 18.89 14.08 18.91 14.48 18.91 14.59 18.86 14.68 18.80 14.75 18.70 14.79 18.59 " +
        "15.94 12.06 15.94 11.90 15.86 11.74 15.72 11.62 15.61 11.59 15.17 11.54 14.56 11.50 " +
        "13.98 11.49 13.38 11.53Z" +
        "M15.76 18.57 15.77 18.71 15.83 18.83 15.92 18.93 16.04 18.99 16.71 19.07 16.86 19.03 " +
        "16.98 18.94 20.01 13.91 20.05 13.80 20.06 13.62 19.98 13.45 19.43 13.04 18.94 12.74 " +
        "18.45 12.47 17.87 12.21 17.35 12.01 17.20 12.01 17.05 12.06 16.93 12.16 16.87 12.31Z" +
        "M8.56 13.40 8.47 13.50 8.43 13.63 8.42 13.77 8.47 13.89 11.48 18.90 11.61 19.02 " +
        "11.79 19.07 12.40 19.00 12.56 18.94 12.64 18.85 12.70 18.76 12.72 18.55 11.63 12.34 " +
        "11.56 12.17 11.42 12.05 11.23 12.00 11.10 12.03 10.68 12.19 10.15 12.42 9.59 12.71 " +
        "9.06 13.04Z" +
        "M5.52 18.39 5.49 18.72 5.51 19.01 5.60 19.31 5.74 19.58 5.94 19.82 6.18 20.01 " +
        "6.46 20.15 6.76 20.22 7.05 20.23 7.33 20.19 8.74 19.73 10.07 19.38 10.18 19.33 " +
        "10.26 19.26 10.32 19.16 10.35 19.05 10.35 18.92 10.30 18.80 7.86 14.74 7.74 14.61 " +
        "7.58 14.55 7.38 14.57 7.22 14.69 6.70 15.37 6.30 16.03 6.04 16.56 5.80 17.17 5.63 17.78Z" +
        "M18.17 18.82 18.13 18.98 18.15 19.13 18.23 19.26 18.36 19.36 19.70 19.72 21.26 20.21 " +
        "21.67 20.23 21.88 20.19 22.08 20.12 22.43 19.92 22.59 19.77 22.73 19.61 22.84 19.42 " +
        "22.93 19.21 22.98 19.00 23.00 18.75 22.92 18.07 22.74 17.36 22.46 16.61 22.11 15.89 " +
        "21.65 15.18 21.34 14.78 21.20 14.62 21.05 14.56 20.90 14.55 20.75 14.61 20.64 14.72Z"

val CafeBakeryIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "CafeBakery",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(CAFE_BAKERY_PATH).toNodes(),
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.EvenOdd,
        )
    }.build()
}
