package io.resupply.karoo.build

import io.resupply.karoo.data.CorridorTuning
import kotlin.math.max

/**
 * How far the rider must move (meters) before the background nearby-refresh re-queries the
 * POI database. Scales with the detour radius so consecutive searches keep ~50% overlap
 * (half the radius), regardless of the setting — a wide radius needn't refetch as often
 * because the search areas overlap heavily. Floored so a tiny radius can't cause a refetch
 * on every GPS tick.
 *
 * In [smart] mode the effective per-category radius can be far smaller than the setting, but
 * the query still fetches out to the extended box radius; key the overlap off *that* so a tight
 * city budget doesn't force a refetch every few metres.
 *
 * Pure (no Android) so it can be unit-tested on the JVM.
 */
fun refetchThresholdMeters(detourMeters: Int, smart: Boolean = false): Double {
    val effectiveRadius = if (smart) CorridorTuning.maxReachMeters(detourMeters, smart = true) else detourMeters
    return max(REFETCH_FLOOR_METERS, effectiveRadius / 2.0)
}

/** Never refetch more often than this, however small the detour radius. */
const val REFETCH_FLOOR_METERS = 100.0
