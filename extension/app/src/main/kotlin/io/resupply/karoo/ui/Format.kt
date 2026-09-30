package io.resupply.karoo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.resupply.karoo.data.OpeningHours
import io.resupply.karoo.data.Poi
import java.util.Calendar
import java.util.Locale

/**
 * Resolve a POI's normalized [OpeningHours.Hours] from whichever source we have:
 * OSM tags first, else an already-fetched Google result ([googleHours]). Returns null
 * when neither is available — the caller shows type only, no open/closed claim.
 */
fun hoursFor(poi: Poi, googleHours: OpeningHours.Hours?): OpeningHours.Hours? =
    poi.tags["opening_hours"]?.let { OpeningHours.Hours.fromOsm(it) } ?: googleHours

// --- Units ------------------------------------------------------------------------------------
//
// Metres are the app's internal distance unit (POI distances, route length, detour radius all
// stay in metres). The formatters below convert to km/m or mi/ft only at display time, driven by
// a resolved [useImperial] boolean so the same call renders either system.

/** One mile in metres, and one foot in metres — the imperial conversion constants. */
const val METERS_PER_MILE = 1609.344
const val METERS_PER_FOOT = 0.3048

// Below this, imperial distances read in feet rather than fractional miles (the mi/ft analog of
// the metric m→km split at 1 km). 0.1 mi ≈ 161 m ≈ 528 ft.
private const val IMPERIAL_FEET_BELOW_MILES = 0.1

/**
 * Human distance. Metric: "180 m" under 1 km, "1.6 km" above. Imperial: "450 ft" under 0.1 mi,
 * "1.6 mi" above.
 */
fun formatDistance(meters: Int, useImperial: Boolean = false): String {
    if (!useImperial) {
        return if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000.0) else "$meters m"
    }
    val miles = meters / METERS_PER_MILE
    return if (miles >= IMPERIAL_FEET_BELOW_MILES) {
        String.format(Locale.US, "%.1f mi", miles)
    } else {
        "${(meters / METERS_PER_FOOT).toInt()} ft"
    }
}

fun formatDistance(meters: Double, useImperial: Boolean = false): String =
    formatDistance(meters.toInt(), useImperial)

/**
 * Compact along-route range for the collapsed range pill, e.g. "20–21 km" / "800–950 m", or in
 * imperial "12–13 mi" / "300–450 ft". Shares one unit across both ends so it reads as a single
 * span, not two independent distances. Switches to the larger unit (km/mi) when the far end is
 * beyond the threshold. If both ends round to the same displayed value (a degenerate span), shows
 * that single value instead of "x–x".
 */
fun formatRangeCompact(startMeters: Double, endMeters: Double, useImperial: Boolean = false): String {
    val lo = minOf(startMeters, endMeters)
    val hi = maxOf(startMeters, endMeters)
    if (useImperial) {
        return if (hi / METERS_PER_MILE >= IMPERIAL_FEET_BELOW_MILES) {
            val a = String.format(Locale.US, "%.1f", lo / METERS_PER_MILE)
            val b = String.format(Locale.US, "%.1f", hi / METERS_PER_MILE)
            if (a == b) "$a mi" else "$a–$b mi"
        } else {
            val a = (lo / METERS_PER_FOOT).toInt()
            val b = (hi / METERS_PER_FOOT).toInt()
            if (a == b) "$a ft" else "$a–$b ft"
        }
    }
    return if (hi >= 1000) {
        val a = String.format(Locale.US, "%.1f", lo / 1000.0)
        val b = String.format(Locale.US, "%.1f", hi / 1000.0)
        if (a == b) "$a km" else "$a–$b km"
    } else {
        val a = lo.toInt()
        val b = hi.toInt()
        if (a == b) "$a m" else "$a–$b m"
    }
}

// Shared status colors (open = green, closed = muted red), used by list + detail. These sit on
// the themed surface, so they're theme-aware: the values below are tuned for the light (cream)
// theme; on the dark brand tile they'd read muddy, so the @Composable accessors brighten them.
val OpenGreen = Color(0xFF2E7D32)
val ClosedRed = Color(0xFFB00020)

// Neutral tint for the "hours exist but are seasonal/complex" badge/chip.
val SeasonalGrey = Color(0xFF757575)

// Dark-theme variants: brighter so they carry on the near-black teal surface.
private val OpenGreenDark = Color(0xFF66BB6A)
private val ClosedRedDark = Color(0xFFEF5350)
private val SeasonalGreyDark = Color(0xFF9AA5A0)

/** Open-status green, brightened on the dark theme so it reads on the brand tile. */
val openGreen: Color
    @Composable get() = if (isSystemInDarkTheme()) OpenGreenDark else OpenGreen

/** Closed-status red, brightened on the dark theme (the deep maroon is invisible on the tile). */
val closedRed: Color
    @Composable get() = if (isSystemInDarkTheme()) ClosedRedDark else ClosedRed

/** Seasonal/unknown grey, lifted on the dark theme. */
val seasonalGrey: Color
    @Composable get() = if (isSystemInDarkTheme()) SeasonalGreyDark else SeasonalGrey

/**
 * Map a light-theme status [color] (as produced by the non-composable pill helpers) to its
 * dark-theme variant when the dark theme is active. A pass-through on the light theme and for
 * colors without a dark variant (e.g. [EtaCloseCallAmber], which reads on both).
 */
@Composable
fun themedStatus(color: Color): Color = if (isSystemInDarkTheme()) {
    when (color) {
        OpenGreen -> OpenGreenDark
        ClosedRed -> ClosedRedDark
        SeasonalGrey -> SeasonalGreyDark
        else -> color
    }
} else {
    color
}

// The "close call" amber for an ETA that lands near an open/close edge — cutting it fine.
// Deeper than the favorites gold so it reads as caution, not a star. Bright enough for both themes.
val EtaCloseCallAmber = Color(0xFFEF6C00)

// The favorites star fill — a warm amber-gold, used by the list/detail/header star toggles
// and the timeline stars so favorites read as one feature.
val FavoriteYellow = Color(0xFFFFB300)

/** Current weekday as an OpeningHours day index (0=Mon … 6=Sun). */
fun todayIndex(now: Calendar = Calendar.getInstance()): Int =
    when (now.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        else -> 6
    }
