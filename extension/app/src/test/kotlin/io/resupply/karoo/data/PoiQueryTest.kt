package io.resupply.karoo.data

import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.RouteIndex
import io.resupply.karoo.util.cumulativeDistances
import io.resupply.karoo.util.distanceToRoute
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

    /**
     * The route-grid speedup must be exact on real data: for every fixture POI within reach, the
     * indexed projection equals the linear [distanceToRoute] field-for-field. This is the guard
     * that [selectAlongRoute]'s output is unchanged by the optimisation (its only use of the
     * index is this projection; candidates beyond reach are dropped identically by both paths).
     */
    @Test
    fun `route index projection matches linear scan on the fixture route`() {
        val cumulative = cumulativeDistances(route)
        val reach = CorridorTuning.maxReachMeters(radius, smart = true).toDouble()
        val index = RouteIndex(route, cumulative, reach)

        var checkedWithinReach = 0
        for (c in candidates) {
            val p = LatLng(c.lat, c.lng)
            val ref = distanceToRoute(route, cumulative, p)
            if (ref.distance > reach) continue
            val got = index.project(p)
            assertEquals("distance for ${c.osmId}", ref.distance, got.distance, 1e-6)
            assertEquals("along for ${c.osmId}", ref.along, got.along, 1e-6)
            assertEquals("side for ${c.osmId}", ref.side, got.side)
            checkedWithinReach++
        }
        assertTrue("checked a meaningful number of in-reach POIs", checkedWithinReach > 100)
    }

    // --- Smart distance -----------------------------------------------------------------

    /** A straight ~2 km route running east; POIs are offset north (cross-track) by the meters
     *  given, all bunched ~100 m along so they land in one segment. */
    private fun straightRoute() = listOf(LatLng(49.0, 8.0), LatLng(49.0, 8.02))
    private fun offsetNorth(id: String, meters: Double, type: String) =
        CandidateInput(id, 49.0 + meters / 111_320.0, 8.001, type)

    /**
     * The core fix: a *full* near band must not drag in far POIs. With enough POIs inside the
     * band, the far ones are dropped entirely — the band never widens. (This is the bug the
     * screenshot showed: near and far both appearing.)
     */
    @Test
    fun `smart keeps only the band when the band is full`() {
        // SMART_MIN_IN_BAND cafés inside the band, plus far ones well beyond it.
        val inBand = (0 until CorridorTuning.SMART_MIN_IN_BAND).map {
            offsetNorth("in$it", 40.0 + it * 20, "COFFEE")
        }
        val far = (0 until 10).map { offsetNorth("far$it", 600.0 + it * 100, "COFFEE") }
        val kept = selectAlongRoute(straightRoute(), inBand + far, radiusMeters = 250, smart = true)
            .map { it.osmId }.toSet()

        assertTrue("keeps the in-band cafés", inBand.all { it.osmId in kept })
        assertTrue("drops every far café — band was full", far.none { it.osmId in kept })
    }

    /** Every kept POI in a full band is within the band distance — never beyond it. */
    @Test
    fun `smart full band admits nothing beyond the band distance`() {
        val cafes = (0 until 8).map { offsetNorth("c$it", 30.0 + it * 15, "COFFEE") } +
            (0 until 8).map { offsetNorth("d$it", 500.0 + it * 60, "COFFEE") }
        val kept = selectAlongRoute(straightRoute(), cafes, radiusMeters = 250, smart = true)
        assertTrue("full band caps distance at the band", kept.isNotEmpty())
        assertTrue(
            "nothing beyond the band distance when the band is full",
            kept.all { it.distanceToRoute <= CorridorTuning.SMART_BAND_METERS },
        )
    }

    /**
     * Sparse band: when the band holds fewer than the minimum, widen for the nearest few out to
     * the reach — so a lone rural POI still surfaces.
     */
    @Test
    fun `smart widens for a lone distant POI when the band is empty`() {
        val lone = offsetNorth("bike0", 800.0, "BIKE_SHOP") // beyond the 150 m band
        val kept = selectAlongRoute(straightRoute(), listOf(lone), radiusMeters = 250, smart = true)
        assertEquals(setOf("bike0"), kept.map { it.osmId }.toSet())
    }

    /** Widening admits at most SMART_MIN_IN_BAND, not an unbounded reach — a single far POI
     *  next to a below-minimum band shouldn't pull in a whole distant cluster. */
    @Test
    fun `smart widening is bounded to the minimum count`() {
        // One café in band (below the min of 2) + a distant cluster of five.
        val one = offsetNorth("near0", 50.0, "COFFEE")
        val cluster = (0 until 5).map { offsetNorth("far$it", 700.0 + it * 30, "COFFEE") }
        val kept = selectAlongRoute(straightRoute(), listOf(one) + cluster, radiusMeters = 250, smart = true)
            .map { it.osmId }.toSet()

        assertTrue("in-band one kept", "near0" in kept)
        // Widened to exactly SMART_MIN_IN_BAND total → only the single nearest far one joins.
        assertEquals("widen keeps only up to the minimum", CorridorTuning.SMART_MIN_IN_BAND, kept.size)
        assertTrue("nearest far one is the one added", "far0" in kept)
    }

    /**
     * The headline behaviour: in one segment, an abundant category stays tight (its band is
     * full) while a scarce category reaches out (its band is empty).
     */
    @Test
    fun `smart diverges per category within one segment`() {
        val cafes = (0 until 10).map { offsetNorth("cafe$it", 30.0 + it * 8, "COFFEE") }
        val loneBike = offsetNorth("bike0", 800.0, "BIKE_SHOP")
        val kept = selectAlongRoute(straightRoute(), cafes + loneBike, radiusMeters = 250, smart = true)
            .map { it.osmId }.toSet()

        assertTrue("lone far bike shop reached", "bike0" in kept)
        // Cafés capped and band-limited: nothing far, and no more than the cap.
        assertTrue("no far café", cafes.filter { it.osmId in kept }.size <= CorridorTuning.SMART_PER_SEGMENT_CAP)
    }

    /**
     * Smart caps a dense category at SMART_PER_SEGMENT_CAP — a cluster of many nearby restaurants
     * is thinned to the ceiling, keeping a long dense route light.
     */
    @Test
    fun `smart caps a dense segment at the smart cap`() {
        val dense = (0 until 20).map { offsetNorth("r$it", 10.0 + it * 2, "FOOD") }
        val kept = selectAlongRoute(straightRoute(), dense, radiusMeters = 250, smart = true)
        assertEquals(CorridorTuning.SMART_PER_SEGMENT_CAP, kept.size)
    }

    /** The max reach is a hard ceiling in smart mode: nothing beyond it. */
    @Test
    fun `smart respects the max reach`() {
        val out = selectAlongRoute(route, candidates, radius, smart = true)
        val maxRadius = CorridorTuning.maxReachMeters(radius, smart = true).toDouble()
        assertTrue("selects some POIs", out.isNotEmpty())
        assertTrue("all within max reach", out.all { it.distanceToRoute <= maxRadius })
    }
}
