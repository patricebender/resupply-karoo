package io.resupply.karoo.data

import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.METERS_PER_DEG_LAT
import io.resupply.karoo.util.RouteIndex
import io.resupply.karoo.util.cumulativeDistances
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * Guards the segmented corridor fetch: the union of [corridorChunkBoxes] must still contain every
 * POI that is actually within reach of the route — i.e. every candidate [selectAlongRoute] could
 * keep. It only drops the far-from-route POIs the old single whole-route bbox over-fetched.
 *
 * Calls the REAL [corridorChunkBoxes] (the box geometry is a pure, DB-free function), so this can't
 * drift from production. The R*Tree fetch is Android-bound and not exercised here — only the boxes.
 */
class SegmentedCorridorTest {

    @Serializable
    private data class FixturePoi(
        val osmId: String, val lat: Double, val lng: Double,
        val type: String, val category: String, val name: String? = null,
    )

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)).bufferedReader().use { it.readText() }

    private fun loadGpx(path: String): List<LatLng> =
        Regex("""<trkpt\s+lat="([0-9.\-]+)"\s+lon="([0-9.\-]+)"""")
            .findAll(resource(path))
            .map { LatLng(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            .toList()

    // Mannheim loop: a dense city route WITH real POIs, for the coverage guarantee.
    private val route: List<LatLng> by lazy { loadGpx("routes/mannheim-loop.gpx") }
    private val pois: List<FixturePoi> by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(resource("routes/mannheim-loop-pois.json"))
    }
    // Schwarzwald traverse: a real 225 km point-to-point route (Heidelberg→Emmendingen), the
    // strung-out shape the segmented fetch targets — geometry only, no POI fixture needed.
    private val longRoute: List<LatLng> by lazy { loadGpx("routes/schwarzwald-traverse.gpx") }

    private fun CorridorBox.contains(lat: Double, lng: Double) =
        lat in minLat..maxLat && lng in minLng..maxLng

    @Test
    fun `segmented boxes contain every in-reach POI`() {
        val radius = 250
        val reach = CorridorTuning.maxReachMeters(radius, smart = true)
        val boxes = corridorChunkBoxes(route, reach)
        val cumulative = cumulativeDistances(route)
        val index = RouteIndex(route, cumulative, reach.toDouble())

        var inReach = 0
        var covered = 0
        for (poi in pois) {
            val proj = index.project(LatLng(poi.lat, poi.lng))
            if (proj.distance > reach) continue     // the single bbox over-fetched these; fine to drop
            inReach++
            if (boxes.any { it.contains(poi.lat, poi.lng) }) covered++
        }
        assertTrue("fixture has in-reach POIs to check", inReach > 100)
        // The union of chunk boxes must miss NONE of the POIs actually within reach of the route.
        assertTrue("in-reach POIs: $covered of $inReach covered by chunk boxes", covered == inReach)
    }

    @Test
    fun `segmented fetch is far tighter on the real long traverse`() {
        // The real 225 km Schwarzwald traverse: a single whole-route bbox is a huge rectangle
        // (~60×148 km) mostly far from the line, while the segmented boxes hug the route. This is
        // the shape the feature targets — the compact city loop is the worst case for it and is
        // covered by the correctness test above, not this one.
        val reach = CorridorTuning.maxReachMeters(250, smart = true)
        val pts = longRoute
        assertTrue("long route loaded", pts.size > 5_000)
        val boxes = corridorChunkBoxes(pts, reach)

        // Union area via a coarse raster (deg² cells) so overlap between chunk boxes isn't
        // double-counted — an honest comparison against the single bbox.
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
        for (p in pts) {
            minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
            minLng = minOf(minLng, p.lng); maxLng = maxOf(maxLng, p.lng)
        }
        val dLat = reach / METERS_PER_DEG_LAT
        val dLng = reach / (METERS_PER_DEG_LAT * cos(Math.toRadians((minLat + maxLat) / 2)))
        val wholeArea = (maxLat + dLat - (minLat - dLat)) * (maxLng + dLng - (minLng - dLng))

        val cell = 0.005
        val unionCells = HashSet<Long>()
        for (b in boxes) {
            var la = b.minLat
            while (la <= b.maxLat) {
                var ln = b.minLng
                while (ln <= b.maxLng) {
                    unionCells.add((Math.floor(la / cell).toLong() shl 32) xor Math.floor(ln / cell).toLong())
                    ln += cell
                }
                la += cell
            }
        }
        val unionArea = unionCells.size * cell * cell

        // On a long diagonal the segmented union should be a small fraction of the whole bbox.
        assertTrue(
            "segmented union ($unionArea) should be well under half the whole bbox ($wholeArea)",
            unionArea < wholeArea * 0.5,
        )
    }
}
