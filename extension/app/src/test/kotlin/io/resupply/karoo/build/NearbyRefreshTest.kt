package io.resupply.karoo.build

import io.resupply.karoo.data.CorridorTuning
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyRefreshTest {

    @Test
    fun `floors small radii at 100m`() {
        // Half of 250 is 125 (above floor); half of 150 is 75 → floored; 100 → 100.
        assertEquals(125.0, refetchThresholdMeters(250), 0.0)
        assertEquals(100.0, refetchThresholdMeters(150), 0.0)
        assertEquals(100.0, refetchThresholdMeters(100), 0.0)
    }

    @Test
    fun `uses half the radius above the floor`() {
        assertEquals(500.0, refetchThresholdMeters(1_000), 0.0)
        assertEquals(2_500.0, refetchThresholdMeters(5_000), 0.0)
    }

    @Test
    fun `smart mode keys off the max reach, not the setting`() {
        // Smart mode reaches to its fixed max regardless of the setting, so the threshold is half
        // that — a tight setting doesn't force a refetch every few metres.
        val expected = CorridorTuning.maxReachMeters(250, smart = true) / 2.0
        assertEquals(expected, refetchThresholdMeters(250, smart = true), 0.0)
        assertEquals(expected, refetchThresholdMeters(5_000, smart = true), 0.0)
    }
}
