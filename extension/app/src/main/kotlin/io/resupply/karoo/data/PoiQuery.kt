package io.resupply.karoo.data

import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.METERS_PER_DEG_LAT
import io.resupply.karoo.util.RouteIndex
import io.resupply.karoo.util.cumulativeDistances
import io.resupply.karoo.util.haversine
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Spatial POI queries against the on-device SQLite (R*Tree). Kotlin port of the
 * backend's poiStore.ts — a route corridor is one bbox R*Tree scan + an exact
 * point-to-segment refine. Runs fully offline, in-process.
 */
class PoiQuery(private val database: PoiDatabase) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * POIs along the route with adaptive density: rural completeness + a hard
     * ceiling in dense areas. The bbox fetch is the only DB-bound step; the
     * ranking/thinning is a pure function ([selectAlongRoute]) so it can be
     * unit-tested off-device against a real route + real POIs. See [selectAlongRoute]
     * for the tunables and the density model.
     */
    fun queryCorridor(
        route: List<LatLng>,
        radiusMeters: Int,
        smart: Boolean = false,
    ): List<Poi> {
        if (route.size < 2) return emptyList()

        // Query with the extended reach so sparse segments (and smart mode's scarce categories)
        // can reach further; the exact per-candidate cutoff is applied in selectAlongRoute.
        val maxRadius = CorridorTuning.maxReachMeters(radiusMeters, smart)

        // Fetch along a SEGMENTED corridor, not one whole-route bbox. A single bbox over a long
        // route that snakes across a region is a huge rectangle mostly far from the line — the
        // R*Tree returns every POI in it, and those all flow through the (linear) selection. A
        // per-chunk bbox instead hugs the local geometry, so the union of fetched rows ≈ the
        // corridor's swept area, independent of route shape. Rows are de-duped by osm id.
        val rows = candidatesAlongRoute(route, maxRadius)

        // Carry each Row through selection so we can emit its parsed tags at the end.
        val byId = HashMap<String, Row>(rows.size)
        val candidates = ArrayList<CandidateInput>(rows.size)
        for (r in rows) {
            byId[r.osmId] = r
            candidates.add(CandidateInput(r.osmId, r.lat, r.lng, r.type))
        }

        val selected = selectAlongRoute(route, candidates, radiusMeters, smart)

        val out = ArrayList<Poi>(selected.size)
        for (s in selected) {
            val row = byId[s.osmId] ?: continue
            out.add(
                row.toPoi(
                    distancesAlongRoute = listOf(s.distanceAlong.roundToInt().toDouble()),
                    detourMeters = s.distanceToRoute.roundToInt(),
                    detourSide = s.side,
                ),
            )
        }
        out.sortBy { it.distancesAlongRoute.firstOrNull() ?: 0.0 }
        return out
    }

    /**
     * POIs within [radiusMeters] of a point (fallback when no route is loaded). When [smart],
     * the fixed radius is ignored: per category, keep the narrow band and only widen to the
     * nearest few when the band is nearly empty (the same band-expansion model as the corridor,
     * see [CorridorTuning]), so a dense area stays tight and a sparse one reaches out.
     */
    fun queryNearby(center: LatLng, radiusMeters: Int, smart: Boolean = false): List<Poi> {
        // Smart mode reaches out (up to the absolute cap) so the density-derived budget has
        // candidates to rank; fixed mode fetches just the base radius.
        val boxRadius = if (smart) CorridorTuning.maxReachMeters(radiusMeters, smart = true) else radiusMeters
        val dLat = boxRadius / METERS_PER_DEG_LAT
        val dLng = boxRadius / (METERS_PER_DEG_LAT * cos(Math.toRadians(center.lat)))
        val rows = candidatesInBox(
            center.lat - dLat, center.lat + dLat, center.lng - dLng, center.lng + dLng,
        )
        if (!smart) {
            return rows
                .filter { haversine(center, LatLng(it.lat, it.lng)) <= radiusMeters }
                .map { it.toPoi(emptyList(), detourMeters = 0) }
        }

        // Band expansion per category, around the rider (no route, so straight-line distance):
        // keep the narrow band; only widen to the nearest few when the band is nearly empty.
        // Then cap so a dense area stays light. Rows with no category are never shown; drop them.
        val withDist = rows.mapNotNull { row ->
            Category.ofType(row.type)?.let { Triple(row, it, haversine(center, LatLng(row.lat, row.lng))) }
        }
        val out = ArrayList<Poi>(withDist.size)
        for ((_, catRows) in withDist.groupBy { it.second }) {
            val byDist = catRows.sortedBy { it.third }
            val inBand = byDist.filter { it.third <= CorridorTuning.SMART_BAND_METERS }
            val eligible = if (inBand.size >= CorridorTuning.SMART_MIN_IN_BAND) {
                inBand
            } else {
                byDist.take(CorridorTuning.SMART_MIN_IN_BAND)
            }
            eligible.take(CorridorTuning.SMART_PER_SEGMENT_CAP)
                .forEach { out.add(it.first.toPoi(emptyList(), detourMeters = 0)) }
        }
        return out
    }

    private inner class Row(
        val osmId: String,
        val lat: Double,
        val lng: Double,
        val type: String,
        val name: String?,
        // Raw `tags` JSON as stored, parsed lazily in [toPoi]. queryCorridor discards most
        // candidates (distance filter + per-segment cap) before emitting, so parsing here
        // would be wasted work for the majority — defer it to the rows we actually return.
        val tagsJson: String?,
    ) {
        fun toPoi(distancesAlongRoute: List<Double>, detourMeters: Int, detourSide: Int = 0) = Poi(
            id = "osm:$osmId",
            lat = lat,
            lng = lng,
            type = type,
            name = name,
            distancesAlongRoute = distancesAlongRoute,
            detourMeters = detourMeters,
            detourSide = detourSide,
            tags = tagsJson?.let(::parseTags) ?: emptyMap(),
        )
    }

    /**
     * Fetch corridor candidates as the union of the tight per-chunk bboxes from
     * [corridorChunkBoxes], de-duped by osm id (chunk boxes overlap at their shared seam vertex).
     */
    private fun candidatesAlongRoute(route: List<LatLng>, reachMeters: Int): List<Row> {
        val byId = LinkedHashMap<String, Row>()   // preserve first-seen order; de-dup seams
        for (b in corridorChunkBoxes(route, reachMeters)) {
            for (r in candidatesInBox(b.minLat, b.maxLat, b.minLng, b.maxLng)) {
                byId.putIfAbsent(r.osmId, r)
            }
        }
        return ArrayList(byId.values)
    }

    /** R*Tree range-scan within a bbox, across all categories. */
    private fun candidatesInBox(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
    ): List<Row> {
        // R*Tree constraints need numeric literals; the bbox values are our own
        // computed doubles (no user input → no injection). All categories are fetched;
        // the enabled-category filter is applied downstream at render time, so a rider
        // can toggle a category on/off without rebuilding.
        val sql = """
            SELECT p.osm_id, p.lat, p.lng, p.type, p.name, p.tags
            FROM poi_rtree r
            JOIN poi p ON p.id = r.id
            WHERE r.maxLat >= $minLat AND r.minLat <= $maxLat
              AND r.maxLng >= $minLng AND r.minLng <= $maxLng
        """.trimIndent()
        val rows = ArrayList<Row>()
        database.writableDatabase().rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        osmId = c.getString(0),
                        lat = c.getDouble(1),
                        lng = c.getDouble(2),
                        type = c.getString(3),
                        name = if (c.isNull(4)) null else c.getString(4),
                        tagsJson = if (c.isNull(5)) null else c.getString(5),
                    ),
                )
            }
        }
        return rows
    }

    /** Parse the stored `tags` JSON blob into a map; empty on any failure. */
    private fun parseTags(jsonText: String): Map<String, String> =
        runCatching {
            json.decodeFromString<Map<String, String>>(jsonText)
        }.getOrDefault(emptyMap())
}

// --- Pure corridor selection (no DB, no Android) --------------------------------------
//
// Extracted from PoiQuery.queryCorridor so the density/relevance logic can be unit-tested
// on the JVM against a real route + real POIs. queryCorridor does the bbox DB fetch, then
// hands the candidates here.

/** An extended lat/lng bounding box to R*Tree-scan for one corridor chunk. */
data class CorridorBox(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double)

/** Route length per corridor-fetch chunk (metres). A distance budget, not a vertex count, so a
 *  chunk is the same geographic size regardless of GPX sampling density. */
private const val CHUNK_METERS = 2_000.0

/**
 * Split [route] into contiguous ~[CHUNK_METERS] chunks and return each chunk's bounding box grown
 * by [reachMeters] on all sides. The union of these tight boxes hugs the corridor, so on a long
 * strung-out route it fetches a fraction of what one whole-route bbox (area ~ extent²) would.
 *
 * Pure and DB-free so it's unit-testable off-device (see SegmentedCorridorTest). Correctness: every
 * vertex lands in some chunk and each box is grown by the full reach, so any POI within reach of any
 * segment is inside some box — the union covers everything the single bbox would that's actually in
 * reach, dropping only the far POIs it over-fetched (which selectAlongRoute discarded anyway).
 * Consecutive chunks share a boundary vertex so the seam segment is fully covered by both.
 */
fun corridorChunkBoxes(route: List<LatLng>, reachMeters: Int): List<CorridorBox> {
    val dLat = reachMeters / METERS_PER_DEG_LAT
    val boxes = ArrayList<CorridorBox>()
    var start = 0
    while (start < route.size - 1) {
        // Grow this chunk vertex by vertex until it has covered ~CHUNK_METERS of route.
        var minLat = route[start].lat; var maxLat = route[start].lat
        var minLng = route[start].lng; var maxLng = route[start].lng
        var chunkLen = 0.0
        var end = start
        while (end < route.size - 1 && chunkLen < CHUNK_METERS) {
            end++
            val p = route[end]
            minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
            minLng = minOf(minLng, p.lng); maxLng = maxOf(maxLng, p.lng)
            chunkLen += haversine(route[end - 1], p)
        }
        // Longitude scale at this chunk's own latitude (not the whole-route mid-lat), so the box
        // stays tight where the route runs far north/south of its overall centre.
        val chunkMidLat = (minLat + maxLat) / 2
        val dLng = reachMeters / (METERS_PER_DEG_LAT * cos(Math.toRadians(chunkMidLat)))
        boxes.add(CorridorBox(minLat - dLat, maxLat + dLat, minLng - dLng, maxLng + dLng))
        start = end   // overlap the next chunk by this boundary vertex
    }
    return boxes
}

/** Density/relevance tunables for [selectAlongRoute]. */
object CorridorTuning {
    // Adaptive density: the route is split into fixed-length segments; within each
    // segment the thinning is applied *per category* — a segment keeps at most
    // PER_SEGMENT_CAP POIs of a given category. Sparse categories may reach beyond the
    // base radius (up to EXTEND_FACTOR×) to surface isolated rural POIs; dense categories
    // stay at the base radius and get thinned to the cap — so a city's cafés can't flood
    // the map. Per-category (not per-segment total) matters: a rare category (bike shops)
    // must not lose slots to an abundant one in the same segment.
    const val SEGMENT_METERS = 2_000.0
    const val PER_SEGMENT_CAP = 12
    const val SPARSE_THRESHOLD = 6      // below this in a segment → allow reach
    const val EXTEND_FACTOR = 2.0       // sparse segments reach up to 2× radius
    const val EXTEND_CAP_METERS = 5_000 // but never beyond this absolute max

    // When a segment's per-category candidates exceed the cap, spread the kept POIs across
    // the segment in sub-slices instead of keeping the N nearest-to-line (which in a town
    // all cluster in one short stretch, leaving the rest of the segment blank).
    const val SUBSLICE_METERS = 500.0

    // Smart distance is a band-expansion model, per category per segment:
    //  1. Keep everything within the narrow base band (SMART_BAND_METERS) — in a dense city this
    //     is full, so smart mode behaves like a tight fixed radius.
    //  2. Only if the band holds fewer than SMART_MIN_IN_BAND does the segment widen, admitting
    //     the nearest few *by detour cost* out to SMART_REACH_METERS — so a rural POI surfaces
    //     when the band is genuinely empty, but a far POI is never dragged in alongside a full
    //     near band.
    //  3. Either way, cap at SMART_PER_SEGMENT_CAP (spread along the segment) so a long dense
    //     route stays light on the map.
    const val SMART_BAND_METERS = 150.0
    const val SMART_MIN_IN_BAND = 2
    const val SMART_REACH_METERS = 2_000.0
    const val SMART_PER_SEGMENT_CAP = 4

    /**
     * The farthest a candidate can be considered. Fixed mode reaches [EXTEND_FACTOR]× the base
     * radius (sparse rural completeness); smart mode reaches to [SMART_REACH_METERS] (its own
     * widen ceiling). Both are clamped to [EXTEND_CAP_METERS].
     */
    fun maxReachMeters(radiusMeters: Int, smart: Boolean): Int =
        if (smart) minOf(SMART_REACH_METERS.toInt(), EXTEND_CAP_METERS)
        else minOf((radiusMeters * EXTEND_FACTOR).toInt(), EXTEND_CAP_METERS)
}

/** A candidate POI fed into [selectAlongRoute] — just the geometry + category source. */
data class CandidateInput(
    val osmId: String,
    val lat: Double,
    val lng: Double,
    /** POI `type` (as stored in the DB); mapped to a [Category] for per-category thinning. */
    val type: String,
)

/** A POI kept by [selectAlongRoute], with its computed route geometry. */
data class SelectedPoi(
    val osmId: String,
    val distanceToRoute: Double,
    val distanceAlong: Double,
    val side: Int,
)

/** Round-trip detour cost: a POI off to the side costs ~2× its cross-track distance to
 *  reach and return. This — not raw cross-track distance — is what ranks convenience. */
private fun detourCost(distanceToRoute: Double): Double = 2.0 * distanceToRoute

/**
 * Rank + thin corridor candidates for relevance. Pure: no DB, no Android.
 *
 * For each along-route segment, thins *per category* (so a rare category keeps its slots
 * regardless of abundant neighbours). Within a category:
 *  - within-radius POIs are always eligible; a sparse segment also reaches beyond the base
 *    radius for the nearest few (rural completeness);
 *  - if still over the per-segment cap, the kept POIs are spread across the segment's
 *    sub-slices (round-robin, best-by-detour-cost first) so no stretch goes blank.
 *
 * Candidates farther than the extended max radius are dropped. Returned in no particular
 * order (the caller sorts by along-route position).
 *
 * When [smart], the base radius is ignored: each category's acceptance distance is derived from
 * its own local density (the band-expansion model, see [CorridorTuning]) — so in one segment an
 * abundant category stays within the narrow band while a scarce one reaches further.
 */
fun selectAlongRoute(
    route: List<LatLng>,
    candidates: List<CandidateInput>,
    radiusMeters: Int,
    smart: Boolean = false,
): List<SelectedPoi> {
    if (route.size < 2 || candidates.isEmpty()) return emptyList()
    val maxRadius = CorridorTuning.maxReachMeters(radiusMeters, smart)

    // Compute geometry and bucket candidates by along-route segment. The route grid makes each
    // candidate's projection test only the nearby segments (cell size = maxRadius, so the true
    // nearest is always in the searched cells → same result as the linear scan).
    val cumulative = cumulativeDistances(route)
    val index = RouteIndex(route, cumulative, maxRadius.toDouble())
    val bySegment = HashMap<Int, MutableList<SelectedPoi>>()
    for (c in candidates) {
        val proj = index.project(LatLng(c.lat, c.lng))
        if (proj.distance > maxRadius) continue
        val seg = (proj.along / CorridorTuning.SEGMENT_METERS).toInt()
        bySegment.getOrPut(seg) { ArrayList() }
            .add(SelectedPoi(c.osmId, proj.distance, proj.along, proj.side))
    }
    // Category lookup by osmId (candidates carry the raw type).
    val catOf = candidates.associate { it.osmId to Category.ofType(it.type) }

    val out = ArrayList<SelectedPoi>()
    for ((_, list) in bySegment) {
        // Thin per category, not across the whole segment: an abundant category (e.g.
        // cafés in a town) must not crowd out a rare one (bike shops).
        for ((_, catList) in list.groupBy { catOf[it.osmId] }) {
            val eligible = if (smart) {
                // Band expansion: keep the narrow band; only widen when it's nearly empty.
                val inBand = catList.filter { it.distanceToRoute <= CorridorTuning.SMART_BAND_METERS }
                if (inBand.size >= CorridorTuning.SMART_MIN_IN_BAND) {
                    inBand
                } else {
                    // Sparse band → reach out (up to maxRadius) for the nearest few, enough to
                    // meet the minimum. A far POI appears only in this branch — never beside a
                    // full band.
                    catList.sortedBy { detourCost(it.distanceToRoute) }
                        .take(CorridorTuning.SMART_MIN_IN_BAND)
                }
            } else {
                // Within-radius POIs are always eligible.
                val withinBase = catList.filter { it.distanceToRoute <= radiusMeters }
                if (withinBase.size < CorridorTuning.SPARSE_THRESHOLD) {
                    // Sparse: also admit the nearest few beyond base radius (rural reach).
                    catList.sortedBy { detourCost(it.distanceToRoute) }
                        .take(CorridorTuning.SPARSE_THRESHOLD)
                } else {
                    withinBase
                }
            }
            val cap = if (smart) CorridorTuning.SMART_PER_SEGMENT_CAP else CorridorTuning.PER_SEGMENT_CAP
            out.addAll(capBySubslice(eligible, cap))
        }
    }
    return out
}

/**
 * Cap a category's eligible POIs to [cap], spreading the kept set across the segment's
 * sub-slices rather than keeping the N nearest-to-line. Buckets by sub-slice (by along-route
 * position), then round-robins over the buckets — each internally sorted best-by-detour-cost —
 * so every populated sub-slice contributes a pick before any gets a second. Under the cap → all
 * kept unchanged.
 */
private fun capBySubslice(
    eligible: List<SelectedPoi>,
    cap: Int = CorridorTuning.PER_SEGMENT_CAP,
): List<SelectedPoi> {
    if (eligible.size <= cap) return eligible

    // Sub-slice buckets, each sorted by ascending detour cost. TreeMap keeps buckets in
    // along-route order so the round-robin walks the segment start→end deterministically.
    val buckets = sortedMapOf<Int, ArrayDeque<SelectedPoi>>()
    for (p in eligible.sortedBy { detourCost(it.distanceToRoute) }) {
        val slice = (p.distanceAlong / CorridorTuning.SUBSLICE_METERS).toInt()
        buckets.getOrPut(slice) { ArrayDeque() }.addLast(p)
    }

    val kept = ArrayList<SelectedPoi>(cap)
    while (kept.size < cap) {
        var tookAny = false
        for (q in buckets.values) {
            if (q.isNotEmpty()) {
                kept.add(q.removeFirst())
                tookAny = true
                if (kept.size == cap) break
            }
        }
        if (!tookAny) break // all buckets drained (shouldn't happen: size > cap)
    }
    return kept
}
