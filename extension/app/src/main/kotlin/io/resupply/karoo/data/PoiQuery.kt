package io.resupply.karoo.data

import io.resupply.karoo.util.LatLng
import io.resupply.karoo.util.METERS_PER_DEG_LAT
import io.resupply.karoo.util.cumulativeDistances
import io.resupply.karoo.util.distanceToRoute
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
        categories: Set<Category>,
    ): List<Poi> {
        if (route.size < 2 || categories.isEmpty()) return emptyList()

        // Query with the extended bbox so sparse segments can reach further.
        val maxRadius = minOf(
            (radiusMeters * CorridorTuning.EXTEND_FACTOR).toInt(),
            CorridorTuning.EXTEND_CAP_METERS,
        )

        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
        for (p in route) {
            minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
            minLng = minOf(minLng, p.lng); maxLng = maxOf(maxLng, p.lng)
        }
        val dLat = maxRadius / METERS_PER_DEG_LAT
        val midLat = (minLat + maxLat) / 2
        val dLng = maxRadius / (METERS_PER_DEG_LAT * cos(Math.toRadians(midLat)))

        val rows = candidatesInBox(
            minLat - dLat, maxLat + dLat, minLng - dLng, maxLng + dLng, categories,
        )

        // Carry each Row through selection so we can emit its parsed tags at the end.
        val byId = HashMap<String, Row>(rows.size)
        val candidates = ArrayList<CandidateInput>(rows.size)
        for (r in rows) {
            byId[r.osmId] = r
            candidates.add(CandidateInput(r.osmId, r.lat, r.lng, r.type))
        }

        val selected = selectAlongRoute(route, candidates, radiusMeters)

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

    /** POIs within [radiusMeters] of a point (fallback when no route is loaded). */
    fun queryNearby(center: LatLng, radiusMeters: Int, categories: Set<Category>): List<Poi> {
        if (categories.isEmpty()) return emptyList()
        val dLat = radiusMeters / METERS_PER_DEG_LAT
        val dLng = radiusMeters / (METERS_PER_DEG_LAT * cos(Math.toRadians(center.lat)))
        return candidatesInBox(
            center.lat - dLat, center.lat + dLat, center.lng - dLng, center.lng + dLng, categories,
        )
            .filter { haversine(center, LatLng(it.lat, it.lng)) <= radiusMeters }
            .map { it.toPoi(emptyList(), detourMeters = 0) }
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

    /** R*Tree range-scan within a bbox, filtered by category. */
    private fun candidatesInBox(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
        categories: Set<Category>,
    ): List<Row> {
        // R*Tree constraints need numeric literals; the bbox values are our own
        // computed doubles (no user input → no injection). Category values are
        // bound as parameters.
        val placeholders = categories.joinToString(",") { "?" }
        val sql = """
            SELECT p.osm_id, p.lat, p.lng, p.type, p.name, p.tags
            FROM poi_rtree r
            JOIN poi p ON p.id = r.id
            WHERE r.maxLat >= $minLat AND r.minLat <= $maxLat
              AND r.maxLng >= $minLng AND r.minLng <= $maxLng
              AND p.category IN ($placeholders)
        """.trimIndent()
        val args = categories.map { it.id }.toTypedArray()
        val rows = ArrayList<Row>()
        database.writableDatabase().rawQuery(sql, args).use { c ->
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
 */
fun selectAlongRoute(
    route: List<LatLng>,
    candidates: List<CandidateInput>,
    radiusMeters: Int,
): List<SelectedPoi> {
    if (route.size < 2 || candidates.isEmpty()) return emptyList()
    val maxRadius = minOf(
        (radiusMeters * CorridorTuning.EXTEND_FACTOR).toInt(),
        CorridorTuning.EXTEND_CAP_METERS,
    )

    // Compute geometry and bucket candidates by along-route segment.
    val cumulative = cumulativeDistances(route)
    val bySegment = HashMap<Int, MutableList<SelectedPoi>>()
    for (c in candidates) {
        val proj = distanceToRoute(route, cumulative, LatLng(c.lat, c.lng))
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
            // Within-radius POIs are always eligible.
            val withinBase = catList.filter { it.distanceToRoute <= radiusMeters }
            val eligible = if (withinBase.size < CorridorTuning.SPARSE_THRESHOLD) {
                // Sparse: also admit the nearest few beyond base radius (rural reach).
                catList.sortedBy { detourCost(it.distanceToRoute) }
                    .take(CorridorTuning.SPARSE_THRESHOLD)
            } else {
                withinBase
            }
            out.addAll(capBySubslice(eligible))
        }
    }
    return out
}

/**
 * Cap a category's eligible POIs to [CorridorTuning.PER_SEGMENT_CAP], spreading the kept
 * set across the segment's sub-slices rather than keeping the N nearest-to-line. Buckets by
 * sub-slice (by along-route position), then round-robins over the buckets — each internally
 * sorted best-by-detour-cost — so every populated sub-slice contributes a pick before any
 * gets a second. Under the cap → all kept unchanged.
 */
private fun capBySubslice(eligible: List<SelectedPoi>): List<SelectedPoi> {
    if (eligible.size <= CorridorTuning.PER_SEGMENT_CAP) return eligible

    // Sub-slice buckets, each sorted by ascending detour cost. TreeMap keeps buckets in
    // along-route order so the round-robin walks the segment start→end deterministically.
    val buckets = sortedMapOf<Int, ArrayDeque<SelectedPoi>>()
    for (p in eligible.sortedBy { detourCost(it.distanceToRoute) }) {
        val slice = (p.distanceAlong / CorridorTuning.SUBSLICE_METERS).toInt()
        buckets.getOrPut(slice) { ArrayDeque() }.addLast(p)
    }

    val kept = ArrayList<SelectedPoi>(CorridorTuning.PER_SEGMENT_CAP)
    while (kept.size < CorridorTuning.PER_SEGMENT_CAP) {
        var tookAny = false
        for (q in buckets.values) {
            if (q.isNotEmpty()) {
                kept.add(q.removeFirst())
                tookAny = true
                if (kept.size == CorridorTuning.PER_SEGMENT_CAP) break
            }
        }
        if (!tookAny) break // all buckets drained (shouldn't happen: size > cap)
    }
    return kept
}
