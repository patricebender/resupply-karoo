package io.resupply.karoo.data

import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.haversine
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
        tags: Map<String, String> = emptyMap(),
    ) = Poi(
        id = id,
        lat = 0.0,
        lng = 0.0,
        type = type,
        name = name,
        distancesAlongRoute = listOf(along),
        detourMeters = detour,
        // Water POIs default to a safe subtype so the ordering/distance tests below aren't
        // silently emptied by the safe-water filter (default on). Safe-water behaviour has
        // its own tests.
        tags = if (type == "REST_STOP" && tags.isEmpty()) mapOf("water_subtype" to "tap") else tags,
    )

    // The route-distance metric the fields/overview pass for a route build.
    private fun aheadOf(progressMeters: Double, toleranceMeters: Double = DEFAULT_TOLERANCE_METERS):
        (Poi) -> Double? = { aheadMetersFor(it, progressMeters, toleranceMeters) }

    @Test
    fun `orders by ahead-on-route and drops passed POIs`() {
        val pois = listOf(
            poi("a", "REST_STOP", along = 1_000.0), // behind the rider
            poi("b", "REST_STOP", along = 6_000.0),
            poi("c", "REST_STOP", along = 3_000.0),
        )
        val out = upcomingByCategory(pois, setOf(Category.WATER), distanceOf = aheadOf(2_000.0))
        val water = out.getValue(Category.WATER)
        // "a" at 1km is behind 2km progress → dropped; remaining ordered nearest-first.
        assertEquals(listOf(1_000.0, 4_000.0), water.map { it.aheadMeters })
    }

    @Test
    fun `takes at most perCat per category`() {
        val pois = (0..9).map { poi("w$it", "REST_STOP", along = (it + 1) * 1_000.0) }
        val out = upcomingByCategory(pois, setOf(Category.WATER), perCat = 3, distanceOf = aheadOf(0.0))
        assertEquals(3, out.getValue(Category.WATER).size)
    }

    @Test
    fun `groups distinct categories independently`() {
        val pois = listOf(
            poi("w", "REST_STOP", along = 5_000.0),
            poi("f", "GAS_STATION", along = 2_000.0),
        )
        val out = upcomingByCategory(
            pois, setOf(Category.WATER, Category.FUEL), distanceOf = aheadOf(0.0),
        )
        assertEquals(5_000.0, out.getValue(Category.WATER).single().aheadMeters, 0.0)
        assertEquals(2_000.0, out.getValue(Category.FUEL).single().aheadMeters, 0.0)
    }

    @Test
    fun `tolerance keeps a POI right at the rider`() {
        val pois = listOf(poi("w", "REST_STOP", along = 1_970.0))
        val out = upcomingByCategory(
            pois, setOf(Category.WATER), distanceOf = aheadOf(2_000.0, toleranceMeters = 50.0),
        )
        // 30m behind but within 50m tolerance → still shown.
        assertEquals(1, out.getValue(Category.WATER).size)
        assertTrue(out.getValue(Category.WATER).single().aheadMeters < 0)
    }

    @Test
    fun `empty enabled set yields empty map`() {
        val pois = listOf(poi("w", "REST_STOP", along = 1_000.0))
        assertTrue(upcomingByCategory(pois, emptySet(), distanceOf = aheadOf(0.0)).isEmpty())
    }

    @Test
    fun `nearby metric orders by straight-line distance from the rider`() {
        // Same category, different locations; rider at origin. Distance grows with longitude.
        val safe = mapOf("water_subtype" to "tap")
        val near = Poi(id = "near", lat = 0.0, lng = 0.001, type = "REST_STOP", name = "near", tags = safe)
        val far = Poi(id = "far", lat = 0.0, lng = 0.01, type = "REST_STOP", name = "far", tags = safe)
        val rider = LatLng(0.0, 0.0)
        val out = upcomingByCategory(listOf(far, near), setOf(Category.WATER)) { p ->
            haversine(rider, LatLng(p.lat, p.lng))
        }
        val water = out.getValue(Category.WATER)
        // Nearest-first: the closer POI leads, and its distance is smaller.
        assertEquals(listOf("near", "far"), water.map { it.name })
        assertTrue(water[0].aheadMeters < water[1].aheadMeters)
    }

    @Test
    fun `nearby metric with no fix drops everything`() {
        val pois = listOf(poi("w", "REST_STOP", along = 0.0))
        val rider: LatLng? = null
        val out = upcomingByCategory(pois, setOf(Category.WATER)) { p ->
            rider?.let { haversine(it, LatLng(p.lat, p.lng)) }
        }
        // No location → null for every POI → empty category list.
        assertTrue(out.getValue(Category.WATER).isEmpty())
    }

    @Test
    fun `safe-water filter hides unknown sources but keeps taps, graveyards and potable`() {
        val pois = listOf(
            poi("tap", "REST_STOP", along = 1_000.0, tags = mapOf("water_subtype" to "tap")),
            poi("grave", "REST_STOP", along = 2_000.0, tags = mapOf("water_subtype" to "graveyard")),
            poi("potable", "REST_STOP", along = 3_000.0,
                tags = mapOf("water_subtype" to "spring", "drinking_water" to "yes")),
            poi("unknown", "REST_STOP", along = 4_000.0,
                tags = mapOf("water_subtype" to "fountain", "drinking_water" to "unknown")),
        )
        val out = upcomingByCategory(pois, setOf(Category.WATER), distanceOf = aheadOf(0.0))
        // The unknown fountain is dropped; the three safe sources remain, in route order.
        assertEquals(listOf(1_000.0, 2_000.0, 3_000.0), out.getValue(Category.WATER).map { it.aheadMeters })
    }

    @Test
    fun `safe-water off keeps every water source`() {
        val pois = listOf(
            poi("tap", "REST_STOP", along = 1_000.0, tags = mapOf("water_subtype" to "tap")),
            poi("unknown", "REST_STOP", along = 2_000.0,
                tags = mapOf("water_subtype" to "fountain", "drinking_water" to "unknown")),
        )
        val out = upcomingByCategory(
            pois, setOf(Category.WATER), safeWaterOnly = false, distanceOf = aheadOf(0.0),
        )
        assertEquals(2, out.getValue(Category.WATER).size)
    }

    @Test
    fun `safe-water filter never touches non-water categories`() {
        // A fuel POI has no water tags; it must not be filtered by the water gate.
        val pois = listOf(poi("f", "GAS_STATION", along = 1_000.0))
        val out = upcomingByCategory(pois, setOf(Category.FUEL), distanceOf = aheadOf(0.0))
        assertEquals(1, out.getValue(Category.FUEL).size)
    }

    @Test
    fun `favoritesUpcoming keeps only starred POIs, still grouped and ordered`() {
        val pois = listOf(
            poi("w1", "REST_STOP", along = 1_000.0),
            poi("w2", "REST_STOP", along = 3_000.0),
            poi("f1", "GAS_STATION", along = 2_000.0),
        )
        val out = favoritesUpcoming(
            pois,
            setOf(Category.WATER, Category.FUEL),
            favoriteIds = setOf("w2", "f1"),
            distanceOf = aheadOf(0.0),
        )
        // Only the starred water POI survives in its category…
        assertEquals(listOf(3_000.0), out.getValue(Category.WATER).map { it.aheadMeters })
        // …and the starred fuel POI in its own.
        assertEquals(listOf(2_000.0), out.getValue(Category.FUEL).map { it.aheadMeters })
    }

    @Test
    fun `favoritesUpcoming with no favorites yields empty map`() {
        val pois = listOf(poi("w", "REST_STOP", along = 1_000.0))
        val out = favoritesUpcoming(pois, setOf(Category.WATER), favoriteIds = emptySet(), distanceOf = aheadOf(0.0))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `favoritesUpcoming still honors category and safe-water gates`() {
        val pois = listOf(
            poi("tap", "REST_STOP", along = 1_000.0, tags = mapOf("water_subtype" to "tap")),
            poi("unknown", "REST_STOP", along = 2_000.0,
                tags = mapOf("water_subtype" to "fountain", "drinking_water" to "unknown")),
        )
        // Both starred, but the unknown source is still dropped by the safe-water filter.
        val out = favoritesUpcoming(
            pois, setOf(Category.WATER), favoriteIds = setOf("tap", "unknown"), distanceOf = aheadOf(0.0),
        )
        assertEquals(listOf(1_000.0), out.getValue(Category.WATER).map { it.aheadMeters })
    }

    @Test
    fun `poiSourceFor maps route length to source`() {
        assertEquals(PoiSource.ROUTE, poiSourceFor(1_234.0))
        assertEquals(PoiSource.NEARBY, poiSourceFor(0.0))
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
