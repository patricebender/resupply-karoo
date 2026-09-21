package io.resupply.karoo.ui

import androidx.compose.ui.graphics.Color
import io.resupply.karoo.data.OpeningHours
import io.resupply.karoo.data.Poi
import java.util.Calendar

/**
 * Resolve a POI's normalized [OpeningHours.Hours] from whichever source we have:
 * OSM tags first, else an already-fetched Google result ([googleHours]). Returns null
 * when neither is available — the caller shows type only, no open/closed claim.
 */
fun hoursFor(poi: Poi, googleHours: OpeningHours.Hours?): OpeningHours.Hours? =
    poi.tags["opening_hours"]?.let { OpeningHours.Hours.fromOsm(it) } ?: googleHours

/** Human distance: "180 m" under 1 km, "1.6 km" above. */
fun formatDistance(meters: Int): String =
    if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "$meters m"

fun formatDistance(meters: Double): String = formatDistance(meters.toInt())

/**
 * Compact along-route range for the collapsed range pill, e.g. "20–21 km" or "800–950 m".
 * Shares one unit across both ends so it reads as a single span, not two independent
 * distances. Uses km when either end is ≥ 1 km. If both ends round to the same displayed
 * value (a degenerate span), shows that single value instead of "x–x".
 */
fun formatRangeCompact(startMeters: Double, endMeters: Double): String {
    val lo = minOf(startMeters, endMeters)
    val hi = maxOf(startMeters, endMeters)
    return if (hi >= 1000) {
        val a = "%.1f".format(lo / 1000.0)
        val b = "%.1f".format(hi / 1000.0)
        if (a == b) "$a km" else "$a–$b km"
    } else {
        val a = lo.toInt()
        val b = hi.toInt()
        if (a == b) "$a m" else "$a–$b m"
    }
}

// Shared status colors (open = green, closed = muted red), used by list + detail.
val OpenGreen = Color(0xFF2E7D32)
val ClosedRed = Color(0xFFB00020)

// Neutral tint for the "hours exist but are seasonal/complex" badge/chip.
val SeasonalGrey = Color(0xFF757575)

// The "close call" amber for an ETA that lands near an open/close edge — cutting it fine.
// Deeper than the favorites gold so it reads as caution, not a star, on the light theme.
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
