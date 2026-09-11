package io.resupply.karoo.data

import io.resupply.karoo.util.LatLng
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corridor selection ([selectAlongRoute]) against a real reference route and real POIs
 * near it (Mannheim loop: dense city core + sparser woods). Fixtures are committed under
 * src/test/resources/routes/ so the test is hermetic — no DB, no gitignored seed. The
 * asserts are structural (per-category isolation, spread, cap, cost ordering) rather than
 * exact-id lists, so a future seed refresh doesn't require rewriting them.
 */
class PoiQueryTest {

    @Serializable
    private data class FixturePoi(
        val osmId: String,
        val lat: Double,
        val lng: Double,
        val type: String,
        val category: String,
        val name: String? = null,
    )

    private val radius = 250 // matches the app default detour radius

    private val route: List<LatLng> by lazy { loadRoute("routes/mannheim-loop.gpx") }
    private val candidates: List<CandidateInput> by lazy {
        loadPois("routes/mannheim-loop-pois.json")
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing test resource: $path"
        }.bufferedReader().use { it.readText() }

    /** Minimal GPX trackpoint parser — no XML dep, just the <trkpt lat lon> attributes. */
    private fun loadRoute(path: String): List<LatLng> {
        val re = Regex("""<trkpt\s+lat="([0-9.\-]+)"\s+lon="([0-9.\-]+)"""")
        return re.findall(resource(path))
    }

    private fun Regex.findall(text: String): List<LatLng> =
        findAll(text).map { m ->
            LatLng(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }.toList()

    private fun loadPois(path: String): List<CandidateInput> {
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString<List<FixturePoi>>(resource(path))
            .map { CandidateInput(it.osmId, it.lat, it.lng, it.type) }
    }

    private fun categoryOf(osmId: String): Category? {
        val type = candidates.first { it.osmId == osmId }.type
        return Category.ofType(type)
    }

    // Group selected POIs by (segment, category) the same way selectAlongRoute does.
    private fun bySegmentCategory(
        selected: List<SelectedPoi>,
    ): Map<Pair<Int, Category?>, List<SelectedPoi>> =
        selected.groupBy {
            val seg = (it.distanceAlong / CorridorTuning.SEGMENT_METERS).toInt()
            seg to categoryOf(it.osmId)
        }

    @Test
    fun `fixtures load and route is a plausible city loop`() {
        assertTrue("route has trackpoints", route.size > 500)
        assertTrue("candidates loaded", candidates.size > 1_000)
        // Loop route: start and end coincide.
        assertEquals(route.first().lat, route.last().lat, 1e-6)
        assertEquals(route.first().lng, route.last().lng, 1e-6)
    }

    @Test
    fun `selects a non-empty, plausible set`() {
        val out = selectAlongRoute(route, candidates, radius)
        assertTrue("selects some POIs", out.isNotEmpty())
        // Never emits anything beyond the extended max radius.
        val maxRadius = minOf(radius * CorridorTuning.EXTEND_FACTOR, CorridorTuning.EXTEND_CAP_METERS.toDouble())
        assertTrue("all within extended radius", out.all { it.distanceToRoute <= maxRadius })
    }

    /**
     * Regression guard for the per-category density fix: a rare category (BIKE) must keep
     * the same POIs whether searched alone or alongside every other category. Before the
     * fix, abundant categories crowded bike shops out of the shared per-segment cap.
     */
    @Test
    fun `rare category keeps the same POIs alone as with all categories`() {
        val bikeOnly = candidates.filter { Category.ofType(it.type) == Category.BIKE }
        assertTrue("fixture has bike shops", bikeOnly.isNotEmpty())

        val aloneIds = selectAlongRoute(route, bikeOnly, radius).map { it.osmId }.toSet()
        val allIds = selectAlongRoute(route, candidates, radius)
            .filter { categoryOf(it.osmId) == Category.BIKE }
            .map { it.osmId }
            .toSet()

        assertEquals("bike selection must not depend on other categories", aloneIds, allIds)
    }

    /** No (segment, category) group ever exceeds the per-segment cap. */
    @Test
    fun `per-category cap is honored in every segment`() {
        val out = selectAlongRoute(route, candidates, radius)
        for ((key, group) in bySegmentCategory(out)) {
            assertTrue(
                "segment/category $key kept ${group.size} > cap ${CorridorTuning.PER_SEGMENT_CAP}",
                group.size <= CorridorTuning.PER_SEGMENT_CAP,
            )
        }
    }

    /**
     * Spread: where a dense segment/category was capped, the kept POIs are distributed
     * across sub-slices rather than all clustered in one 500 m stretch. Assert that at
     * least one capped group spans >1 sub-slice (proving the round-robin runs), and that no
     * capped group collapses to a single sub-slice when the eligible pool spanned several.
     */
    @Test
    fun `capped dense segments spread across sub-slices`() {
        val out = selectAlongRoute(route, candidates, radius)
        val cappedGroups = bySegmentCategory(out)
            .values
            .filter { it.size == CorridorTuning.PER_SEGMENT_CAP }
        assertTrue("expected at least one capped group in a city route", cappedGroups.isNotEmpty())

        val spread = cappedGroups.any { group ->
            group.map { (it.distanceAlong / CorridorTuning.SUBSLICE_METERS).toInt() }
                .distinct().size > 1
        }
        assertTrue("capped groups should span multiple sub-slices, not cluster", spread)
    }

    /**
     * When a single dense cluster (all in one sub-slice) is capped, selection keeps the
     * nearest-to-line POIs — detour cost is monotonic in cross-track distance, so ordering
     * matches the old nearest-first behavior. Synthetic input: a tight cluster of
     * PER_SEGMENT_CAP+extras at increasing offsets on one short stretch.
     */
    @Test
    fun `single-cluster cap keeps the nearest to the line`() {
        // A straight ~1km route running east; POIs offset north by increasing amounts, all
        // within the same 500m sub-slice near the start.
        val straight = listOf(LatLng(49.0, 8.0), LatLng(49.0, 8.02))
        val n = CorridorTuning.PER_SEGMENT_CAP + 5
        val cluster = (0 until n).map { i ->
            // ~ (i+1) * 8m north of the line, bunched around 100m along.
            CandidateInput("c$i", 49.0 + (i + 1) * 0.00007, 8.001, "BIKE_SHOP")
        }
        val kept = selectAlongRoute(straight, cluster, radiusMeters = 3_000)
        assertEquals(CorridorTuning.PER_SEGMENT_CAP, kept.size)
        // Kept must be exactly the PER_SEGMENT_CAP nearest offsets (c0..c11), none of the
        // farther ones.
        val keptIds = kept.map { it.osmId }.toSet()
        val expected = (0 until CorridorTuning.PER_SEGMENT_CAP).map { "c$it" }.toSet()
        assertEquals(expected, keptIds)
    }
}
