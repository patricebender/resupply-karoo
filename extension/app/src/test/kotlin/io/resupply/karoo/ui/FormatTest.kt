package io.resupply.karoo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `formatDistance metric keeps existing m and km output`() {
        assertEquals("180 m", formatDistance(180))
        assertEquals("1.6 km", formatDistance(1_600))
        assertEquals("12.4 km", formatDistance(12_400))
    }

    @Test
    fun `formatDistance imperial uses feet under 0-1 mi then miles`() {
        // 137 m ≈ 0.085 mi → feet (137 / 0.3048 ≈ 449).
        assertEquals("449 ft", formatDistance(137, useImperial = true))
        // 483 m ≈ 0.3 mi → miles.
        assertEquals("0.3 mi", formatDistance(483, useImperial = true))
        // 2575 m ≈ 1.6 mi.
        assertEquals("1.6 mi", formatDistance(2_575, useImperial = true))
        // 19 312 m ≈ 12.0 mi.
        assertEquals("12.0 mi", formatDistance(19_312, useImperial = true))
    }

    @Test
    fun `formatRangeCompact metric unchanged`() {
        assertEquals("800–950 m", formatRangeCompact(800.0, 950.0))
        assertEquals("20.0–21.0 km", formatRangeCompact(20_000.0, 21_000.0))
        // Degenerate span collapses to a single value.
        assertEquals("500 m", formatRangeCompact(500.0, 500.0))
    }

    @Test
    fun `formatRangeCompact imperial spans in ft then mi`() {
        // Both ends under 0.1 mi → feet.
        assertEquals("328–410 ft", formatRangeCompact(100.0, 125.0, useImperial = true))
        // Far end past 0.1 mi → miles.
        assertEquals("12.4–13.0 mi", formatRangeCompact(20_000.0, 20_921.0, useImperial = true))
    }
}
