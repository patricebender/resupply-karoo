package io.resupply.karoo.data

import io.resupply.karoo.util.decodeElevationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elevation-along-route math for the climb-to-POI cue: profile decode, interpolation, signed net
 * gain, the POI-facing helper (same crossing as [aheadMetersFor]), and the display formatter.
 */
class RouteElevationTest {

    // --- decode (precision-1 (distance, elevation) polyline) --------------------------------

    @Test
    fun `decodeElevationProfile round-trips a known series`() {
        // (distanceAlongRoute, elevation) in metres, ascending by distance.
        val series = listOf(
            0.0 to 100.0,
            1_000.0 to 150.0,
            2_000.0 to 120.0,
            5_000.0 to 300.0,
        )
        val encoded = encodeP1(series)
        val profile = decodeElevationProfile(encoded)
        assertEquals(series.size, profile.distances.size)
        series.forEachIndexed { i, (d, e) ->
            // Precision 1 → one decimal place, so round-trip is exact to 0.1 m.
            assertEquals(d, profile.distances[i], 0.05)
            assertEquals(e, profile.elevations[i], 0.05)
        }
    }

    @Test
    fun `blank polyline decodes to the empty profile`() {
        assertTrue(decodeElevationProfile("").isEmpty)
    }

    // --- elevationAt (binary search + linear interpolation) ---------------------------------

    @Test
    fun `elevationAt interpolates between samples`() {
        val p = profileOf(0.0 to 100.0, 1_000.0 to 200.0)
        // Halfway along the only segment → halfway up (150 m).
        assertEquals(150.0, p.elevationAt(500.0)!!, 1e-6)
        // A quarter in → 125 m.
        assertEquals(125.0, p.elevationAt(250.0)!!, 1e-6)
    }

    @Test
    fun `elevationAt clamps outside the sampled range`() {
        val p = profileOf(100.0 to 50.0, 900.0 to 90.0)
        assertEquals(50.0, p.elevationAt(0.0)!!, 1e-6)      // before first → first
        assertEquals(90.0, p.elevationAt(10_000.0)!!, 1e-6) // past last → last
    }

    @Test
    fun `elevationAt is null on the empty profile`() {
        assertNull(ElevationProfile.EMPTY.elevationAt(500.0))
    }

    // --- ascentTo (total climb, descents ignored) ------------------------------------------

    @Test
    fun `ascentTo sums only the uphills`() {
        // Up 50, down 30, up 180: total ascent = 50 + 180 = 230 (the −30 descent is ignored).
        val p = profileOf(0.0 to 100.0, 1_000.0 to 150.0, 2_000.0 to 120.0, 3_000.0 to 300.0)
        assertEquals(230.0, ascentTo(p, 0.0, 3_000.0)!!, 1e-6)
    }

    @Test
    fun `ascentTo is zero across a pure descent`() {
        val p = profileOf(0.0 to 300.0, 2_000.0 to 100.0)
        assertEquals(0.0, ascentTo(p, 0.0, 2_000.0)!!, 1e-6)
    }

    @Test
    fun `ascentTo is net-blind - same endpoints, very different climb`() {
        // Starts and ends at 100 m (net 0) but climbs 200 m to a peak and back: ascent must be 200,
        // NOT 0. This is the whole reason we switched off net gain.
        val p = profileOf(0.0 to 100.0, 1_000.0 to 300.0, 2_000.0 to 100.0)
        assertEquals(200.0, ascentTo(p, 0.0, 2_000.0)!!, 1e-6)
    }

    @Test
    fun `ascentTo interpolates partial endpoint segments`() {
        // One climbing segment 100→300 over 1000 m. From 250 m to 750 m is the middle half → climbs
        // from 150 (interp) to 250 (interp) = 100 m.
        val p = profileOf(0.0 to 100.0, 1_000.0 to 300.0)
        assertEquals(100.0, ascentTo(p, 250.0, 750.0)!!, 1e-6)
    }

    @Test
    fun `ascentTo is zero for a backwards or empty span, null on empty profile`() {
        val p = profileOf(0.0 to 100.0, 1_000.0 to 300.0)
        assertEquals(0.0, ascentTo(p, 500.0, 500.0)!!, 1e-6) // zero-length
        assertEquals(0.0, ascentTo(p, 800.0, 200.0)!!, 1e-6) // backwards
        assertNull(ascentTo(ElevationProfile.EMPTY, 0.0, 1_000.0))
    }

    // --- ascentForPoi (picks the same crossing as aheadMetersFor) ---------------------------

    private fun poi(along: List<Double>) = Poi(
        id = "p", lat = 0.0, lng = 0.0, type = "REST_STOP",
        distancesAlongRoute = along,
    )

    @Test
    fun `ascentForPoi measures climb to the crossing still ahead`() {
        val p = profileOf(0.0 to 100.0, 3_000.0 to 250.0, 5_000.0 to 180.0)
        // Rider at 1km, POI at 3km: climb from 150 (interp @1km) to 250 = 100 m.
        assertEquals(100.0, ascentForPoi(poi(listOf(3_000.0)), 1_000.0, p)!!, 1e-6)
    }

    @Test
    fun `ascentForPoi on a loop uses the nearest crossing ahead`() {
        val p = profileOf(0.0 to 100.0, 2_000.0 to 140.0, 4_000.0 to 300.0)
        // Crossings at 1km (behind) and 4km (ahead) for a rider at 2km → climb to 4km.
        val gain = ascentForPoi(poi(listOf(1_000.0, 4_000.0)), 2_000.0, p)!!
        assertEquals(ascentTo(p, 2_000.0, 4_000.0)!!, gain, 1e-6)
    }

    @Test
    fun `ascentForPoi is null for a passed POI`() {
        val p = profileOf(0.0 to 100.0, 5_000.0 to 300.0)
        assertNull(ascentForPoi(poi(listOf(1_000.0)), 4_000.0, p))
    }

    @Test
    fun `ascentForPoi is null with no profile`() {
        assertNull(ascentForPoi(poi(listOf(1_000.0)), 0.0, ElevationProfile.EMPTY))
    }

    @Test
    fun `ascentFromStartForPoi climbs from the route start, ignoring rider position`() {
        // Up 200 to a peak then down to the POI at 3km: total climb from start = 200.
        val p = profileOf(0.0 to 100.0, 1_500.0 to 300.0, 3_000.0 to 150.0)
        assertEquals(200.0, ascentFromStartForPoi(poi(listOf(3_000.0)), p)!!, 1e-6)
    }

    @Test
    fun `ascentFromStartForPoi uses the first-listed crossing and is null on empty profile`() {
        val p = profileOf(0.0 to 100.0, 2_000.0 to 260.0, 5_000.0 to 400.0)
        // firstOrNull (list order, matching the distance mark), not min: [5000, 2000] → 5000 →
        // climb over [0, 5km] = 300. Pairs the climb with the same crossing the "on route" mark shows.
        assertEquals(300.0, ascentFromStartForPoi(poi(listOf(5_000.0, 2_000.0)), p)!!, 1e-6)
        assertNull(ascentFromStartForPoi(poi(listOf(2_000.0)), ElevationProfile.EMPTY))
    }

    // --- formatters -------------------------------------------------------------------------

    @Test
    fun `formatElevation is unsigned metres or feet`() {
        assertEquals("120 m", formatElevation(120.0))
        assertEquals("0 m", formatElevation(0.0))
        assertEquals("5 m", formatElevation(4.6)) // rounds
        // 120 m / 0.3048 ≈ 394 ft.
        assertEquals("394 ft", formatElevation(120.0, useImperial = true))
    }

    @Test
    fun `formatAscentCompact drops the space and kilos large climbs`() {
        assertEquals("120m", formatAscentCompact(120.0))
        assertEquals("2.1km", formatAscentCompact(2_140.0))
        assertEquals("0m", formatAscentCompact(0.0))
        // Imperial: 120 m ≈ 394 ft (under 1000 → feet); a big climb kilos the feet.
        assertEquals("394ft", formatAscentCompact(120.0, useImperial = true))
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun profileOf(vararg pairs: Pair<Double, Double>) = ElevationProfile(
        distances = DoubleArray(pairs.size) { pairs[it].first },
        elevations = DoubleArray(pairs.size) { pairs[it].second },
    )

    /**
     * Encode (distance, elevation) pairs as a Google polyline at precision 1 — the inverse of the
     * decoder under test, so the round-trip genuinely exercises the decode path (not a fixture the
     * decoder also produced).
     */
    private fun encodeP1(points: List<Pair<Double, Double>>): String {
        val factor = 10.0
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for ((d, e) in points) {
            val lat = Math.round(d * factor)
            val lng = Math.round(e * factor)
            encodeSigned(lat - prevLat, sb)
            encodeSigned(lng - prevLng, sb)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun encodeSigned(v: Long, sb: StringBuilder) {
        var value = v shl 1
        if (v < 0) value = value.inv()
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        sb.append((value.toInt() + 63).toChar())
    }
}
