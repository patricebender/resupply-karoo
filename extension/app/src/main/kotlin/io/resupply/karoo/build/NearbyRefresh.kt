package io.resupply.karoo.build

import kotlin.math.max

/**
 * How far the rider must move (meters) before the background nearby-refresh re-queries the
 * POI database. Scales with the detour radius so consecutive searches keep ~50% overlap
 * (half the radius), regardless of the setting — a wide radius needn't refetch as often
 * because the search areas overlap heavily. Floored so a tiny radius can't cause a refetch
 * on every GPS tick.
 *
 * Pure (no Android) so it can be unit-tested on the JVM.
 */
fun refetchThresholdMeters(detourMeters: Int): Double =
    max(REFETCH_FLOOR_METERS, detourMeters / 2.0)

/** Never refetch more often than this, however small the detour radius. */
const val REFETCH_FLOOR_METERS = 100.0
