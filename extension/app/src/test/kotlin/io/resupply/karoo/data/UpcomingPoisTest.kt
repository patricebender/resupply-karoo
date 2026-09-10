package io.resupply.karoo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingPoisTest {

    private fun poi(
        id: String,
        type: String,
        along: Double,
        detour: Int = 0,
        name: String? = null,
    ) = Poi(
        id = id,
        lat = 0.0,
        lng = 0.0,
        type = type,
        name = name,
        distancesAlongRoute = listOf(along),
        detourMeters = detour,
    )

    @Test
    fun `orders by ahead-on-route and drops passed POIs`() {
        val pois = listOf(
            poi("a", "REST_STOP", along = 1_000.0), // behind the rider
            poi("b", "REST_STOP", along = 6_000.0),
            poi("c", "REST_STOP", along = 3_000.0),
        )
        val out = upcomingByCategory(pois, setOf(Category.WATER), progressMeters = 2_000.0)
        val water = out.getValue(Category.WATER)
        // "a" at 1km is behind 2km progress → dropped; remaining ordered nearest-first.
        assertEquals(listOf(1_000.0, 4_000.0), water.map { it.aheadMeters })
    }

    @Test
    fun `takes at most perCat per category`() {
        val pois = (0..9).map { poi("w$it", "REST_STOP", along = (it + 1) * 1_000.0) }
        val out = upcomingByCategory(pois, setOf(Category.WATER), progressMeters = 0.0, perCat = 3)
        assertEquals(3, out.getValue(Category.WATER).size)
    }

    @Test
    fun `groups distinct categories independently`() {
        val pois = listOf(
            poi("w", "REST_STOP", along = 5_000.0),
            poi("f", "GAS_STATION", along = 2_000.0),
        )
        val out = upcomingByCategory(
            pois, setOf(Category.WATER, Category.FUEL), progressMeters = 0.0,
        )
        assertEquals(5_000.0, out.getValue(Category.WATER).single().aheadMeters, 0.0)
        assertEquals(2_000.0, out.getValue(Category.FUEL).single().aheadMeters, 0.0)
    }

    @Test
    fun `tolerance keeps a POI right at the rider`() {
        val pois = listOf(poi("w", "REST_STOP", along = 1_970.0))
        val out = upcomingByCategory(
            pois, setOf(Category.WATER), progressMeters = 2_000.0, toleranceMeters = 50.0,
        )
        // 30m behind but within 50m tolerance → still shown.
        assertEquals(1, out.getValue(Category.WATER).size)
        assertTrue(out.getValue(Category.WATER).single().aheadMeters < 0)
    }

    @Test
    fun `empty enabled set yields empty map`() {
        val pois = listOf(poi("w", "REST_STOP", along = 1_000.0))
        assertTrue(upcomingByCategory(pois, emptySet(), progressMeters = 0.0).isEmpty())
    }

    @Test
    fun `aheadMetersFor returns distance ahead and null when passed`() {
        val p = poi("w", "REST_STOP", along = 5_000.0)
        assertEquals(3_000.0, aheadMetersFor(p, progressMeters = 2_000.0)!!, 0.0)
        // Well behind → dropped.
        assertNull(aheadMetersFor(p, progressMeters = 6_000.0))
    }

    @Test
    fun `aheadMetersFor keeps a POI just behind within tolerance`() {
        val p = poi("w", "REST_STOP", along = 1_970.0)
        // 30m behind, tolerance 50m → kept, negative ahead.
        assertTrue(aheadMetersFor(p, progressMeters = 2_000.0, toleranceMeters = 50.0)!! < 0)
        // Same POI 60m behind, tolerance 50m → dropped.
        assertNull(aheadMetersFor(p, progressMeters = 2_030.0, toleranceMeters = 50.0))
    }

    @Test
    fun `aheadMetersFor picks the nearest crossing still ahead`() {
        val p = Poi(
            id = "loop", lat = 0.0, lng = 0.0, type = "REST_STOP",
            distancesAlongRoute = listOf(1_000.0, 4_000.0, 8_000.0),
        )
        // At 2km, the 1km crossing is behind; nearest ahead is 4km → 2km ahead.
        assertEquals(2_000.0, aheadMetersFor(p, progressMeters = 2_000.0)!!, 0.0)
    }

    @Test
    fun `behindMetersFor is null when ahead, positive once passed`() {
        val p = poi("w", "REST_STOP", along = 3_000.0)
        // Still ahead → null.
        assertNull(behindMetersFor(p, progressMeters = 1_000.0))
        // Passed by 2km → 2000 behind.
        assertEquals(2_000.0, behindMetersFor(p, progressMeters = 5_000.0)!!, 0.0)
    }

    @Test
    fun `behindMetersFor uses the nearest crossing behind on a loop`() {
        val p = Poi(
            id = "loop", lat = 0.0, lng = 0.0, type = "REST_STOP",
            distancesAlongRoute = listOf(1_000.0, 4_000.0),
        )
        // At 6km both crossings are behind; nearest is 4km → 2km back.
        assertEquals(2_000.0, behindMetersFor(p, progressMeters = 6_000.0)!!, 0.0)
    }

    @Test
    fun `formatKm keeps one decimal under 10km and rounds above`() {
        assertEquals("5.2km", formatKm(5_240.0))
        assertEquals("0.8km", formatKm(800.0))
        assertEquals("12km", formatKm(12_400.0))
        assertEquals("0.0km", formatKm(-5.0)) // clamped
    }

    @Test
    fun `formatDetour omits zero and formats positive`() {
        assertEquals("", formatDetour(0))
        assertEquals("·+200m", formatDetour(200))
    }

    @Test
    fun `elideName truncates long names and passes null`() {
        assertNull(elideName(null, 10))
        assertEquals("Rewe", elideName("Rewe", 10))
        assertEquals("Rewe Getr…", elideName("Rewe Getränkemarkt", 10))
    }
}
