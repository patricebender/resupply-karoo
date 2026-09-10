package io.resupply.karoo.data

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.resupply.karoo.BuildConfig
import io.resupply.karoo.util.httpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-demand opening-hours lookup via Google Places API (New), for POIs where OSM has
 * no `opening_hours` (common for fuel stations, some cafés/shops). Two steps:
 *   1. Text Search → resolve our name+coords to a Google Place ID (cacheable forever
 *      per Maps ToS; we persist it so repeat checks skip this call).
 *   2. Place Details → fetch `regularOpeningHours`.
 *
 * **Hours are never persisted** — Maps ToS forbids caching Places content beyond
 * Place IDs and coordinates. Callers hold the result in memory for the current view
 * only and display it live with "via Google" attribution.
 *
 * Routed through the Karoo HTTP bridge like [WikipediaClient], so it works over the
 * paired phone. Disabled (returns null) when [BuildConfig.PLACES_API_KEY] is empty.
 */
class PlacesClient(private val system: KarooSystemService) {

    private val json = Json { ignoreUnknownKeys = true }
    private val apiKey = BuildConfig.PLACES_API_KEY

    val isConfigured: Boolean get() = apiKey.isNotEmpty()

    /**
     * Resolved place: hours as the same normalized model OSM produces, plus the extra
     * contact fields so a Google-sourced POI can render identically to an OSM one.
     *
     * [address], [website], [phone] are Places *content* under the same Maps ToS as the
     * hours — displayable live (with attribution), never persisted. They ride in the same
     * short-TTL in-memory cache as [hours], so this doesn't regress the caching policy.
     */
    data class Result(
        val placeId: String,
        val hours: OpeningHours.Hours,
        val address: String? = null,
        val website: String? = null,
        val phone: String? = null,
    )

    /**
     * Look up hours for a POI. [knownPlaceId] skips the Text Search when we've already
     * resolved and cached the Place ID. Returns null when unconfigured or on any failure.
     */
    suspend fun hoursFor(
        name: String?,
        lat: Double,
        lng: Double,
        knownPlaceId: String? = null,
    ): Result? {
        if (!isConfigured) return null
        val placeId = knownPlaceId ?: resolvePlaceId(name, lat, lng) ?: return null
        return details(placeId)
    }

    /**
     * Text Search (New) restricted to a tight box around the POI → best-match Place ID.
     *
     * A generic brand name ("Shell", "Aldi") matches many places, and [locationBias] is
     * only a *soft* nudge: Google will happily return a higher-ranked branch a kilometre
     * away, so we'd show one station's hours/website for a different one. Two guards:
     *   1. [locationRestriction] — a hard [MATCH_RADIUS_M] viewport box; results outside
     *      it are never returned.
     *   2. We still fetch each candidate's `location` and keep only one within
     *      [MATCH_RADIUS_M] of the POI, so a wrong-but-inside-the-box match fails cleanly
     *      to "not found" rather than surfacing the wrong place's data.
     */
    private suspend fun resolvePlaceId(name: String?, lat: Double, lng: Double): String? {
        val query = name?.trim().orEmpty().ifEmpty { return null }
        val box = boundingBox(lat, lng, MATCH_RADIUS_M)
        val body = buildString {
            append("{\"textQuery\":").append(jsonString(query)).append(",")
            append("\"maxResultCount\":5,")
            append("\"locationRestriction\":{\"rectangle\":{")
            append("\"low\":{\"latitude\":").append(box.south).append(",\"longitude\":").append(box.west).append("},")
            append("\"high\":{\"latitude\":").append(box.north).append(",\"longitude\":").append(box.east).append("}")
            append("}}}")
        }
        val complete = post(
            url = "https://places.googleapis.com/v1/places:searchText",
            body = body,
            fieldMask = "places.id,places.location",
        ) ?: return null
        val responseBody = complete.body
        if (complete.statusCode !in 200..299 || responseBody == null) {
            Timber.d("places textSearch failed: ${complete.statusCode} ${complete.error}")
            return null
        }
        return runCatching {
            val places = json.parseToJsonElement(String(responseBody, Charsets.UTF_8))
                .jsonObject["places"]?.jsonArray ?: return@runCatching null
            // Keep the nearest candidate that's genuinely close; drop anything farther than
            // the match radius even if Google ranked it first.
            places
                .mapNotNull { el ->
                    val o = el.jsonObject
                    val loc = o["location"]?.jsonObject ?: return@mapNotNull null
                    val pLat = loc["latitude"]?.jsonPrimitive?.double ?: return@mapNotNull null
                    val pLng = loc["longitude"]?.jsonPrimitive?.double ?: return@mapNotNull null
                    val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    id to haversineM(lat, lng, pLat, pLng)
                }
                .filter { it.second <= MATCH_RADIUS_M }
                .minByOrNull { it.second }
                ?.first
        }.getOrNull()
    }

    /**
     * Place Details (New) → hours + contact fields, so a Google-sourced POI renders like
     * an OSM one. Returns a Result whenever the place resolves, even with no hours (empty
     * schedule → "unknown"), so the address/website still surface.
     */
    private suspend fun details(placeId: String): Result? {
        val complete = get(
            url = "https://places.googleapis.com/v1/places/$placeId",
            fieldMask = "id,regularOpeningHours,formattedAddress,websiteUri,nationalPhoneNumber",
        ) ?: return null
        val body = complete.body
        if (complete.statusCode !in 200..299 || body == null) {
            Timber.d("places details failed: ${complete.statusCode} ${complete.error}")
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
            val hoursObj = root["regularOpeningHours"]?.jsonObject
            val hours = hoursObj?.let { OpeningHours.Hours.fromSchedule(parsePeriods(it)) }
                // No hours from Google → an empty (unknown) schedule; contact still shows.
                ?: OpeningHours.Hours.fromSchedule(emptyMap())
            Result(
                placeId = placeId,
                hours = hours,
                address = root["formattedAddress"]?.jsonPrimitive?.content,
                website = root["websiteUri"]?.jsonPrimitive?.content,
                phone = root["nationalPhoneNumber"]?.jsonPrimitive?.content,
            )
        }.onFailure { Timber.w(it, "places details parse failed") }.getOrNull()
    }

    /**
     * Convert Google `periods` (day 0=Sunday, open/close with hour+minute) into our
     * schedule keyed 0=Monday..6=Sun.
     *
     * Google encodes an always-open business as a *single* period with an `open` at
     * Sunday 00:00 and **no `close` field at all** — not seven daily periods. Mapping
     * that literally (open Sunday, silent every other day) is what produced the
     * "closed every day but Sunday" bug. So we detect that shape and return a full
     * seven-day 00:00–24:00 week, which [OpeningHours.Hours.fromSchedule] recognizes
     * as 24/7 and renders as a single "Open 24/7" line.
     *
     * For normal businesses every period carries both `open` and `close`; a period
     * that spans midnight is clipped to its start day (good enough for the table).
     */
    private fun parsePeriods(hours: kotlinx.serialization.json.JsonObject): Map<Int, List<OpeningHours.TimeRange>> {
        val periods = hours["periods"]?.jsonArray ?: return emptyMap()

        // Always-open: a lone open-ended period (no close) → open all week.
        val single = periods.singleOrNull()?.jsonObject
        if (single != null && single["close"] == null && single["open"] != null) {
            return (0..6).associateWith { listOf(OpeningHours.TimeRange(0, 24 * 60)) }
        }

        val out = HashMap<Int, MutableList<OpeningHours.TimeRange>>()
        for (p in periods) {
            val po = p.jsonObject
            val open = po["open"]?.jsonObject ?: continue
            val close = po["close"]?.jsonObject ?: continue // no close only ever means 24/7 (handled above)
            val gDay = open["day"]?.jsonPrimitive?.int ?: continue
            val startMin = (open["hour"]?.jsonPrimitive?.int ?: 0) * 60 +
                (open["minute"]?.jsonPrimitive?.int ?: 0)
            val endMin = (close["hour"]?.jsonPrimitive?.int ?: 0) * 60 +
                (close["minute"]?.jsonPrimitive?.int ?: 0)
            val day = googleDayToMonFirst(gDay)
            out.getOrPut(day) { mutableListOf() }.add(OpeningHours.TimeRange(startMin, endMin))
        }
        return out
    }

    /** Google 0=Sun..6=Sat → our 0=Mon..6=Sun. */
    private fun googleDayToMonFirst(gDay: Int): Int = (gDay + 6) % 7

    // ---- Geo: keep a brand-name match glued to the POI's actual coordinates ----

    /**
     * How close a candidate must be to the OSM POI to count as the same place. OSM and
     * Google rarely agree to the metre (different survey points, forecourt vs. shop
     * entrance), so this is loose enough to tolerate that but far tighter than the spacing
     * between two distinct branches of the same brand.
     */
    private val MATCH_RADIUS_M = 150.0

    private data class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    /** A lat/lng box roughly [radiusM] around a point — the Text Search restriction viewport. */
    private fun boundingBox(lat: Double, lng: Double, radiusM: Double): Box {
        val dLat = radiusM / 111_320.0
        val dLng = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        return Box(lat - dLat, lng - dLng, lat + dLat, lng + dLng)
    }

    /** Great-circle distance in metres between two lat/lng points. */
    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dP = Math.toRadians(lat2 - lat1)
        val dL = Math.toRadians(lng2 - lng1)
        val a = sin(dP / 2).pow(2) + cos(p1) * cos(p2) * sin(dL / 2).pow(2)
        return 2 * r * asin(sqrt(a).coerceAtMost(1.0))
    }

    // ---- HTTP through the Karoo bridge ----

    private suspend fun post(url: String, body: String, fieldMask: String) =
        request("POST", url, body.toByteArray(Charsets.UTF_8), fieldMask)

    private suspend fun get(url: String, fieldMask: String) =
        request("GET", url, null, fieldMask)

    private suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
        fieldMask: String,
    ): HttpResponseState.Complete? =
        system.httpRequest(
            method = method,
            url = url,
            headers = mapOf(
                "X-Goog-Api-Key" to apiKey,
                "X-Goog-FieldMask" to fieldMask,
                "Content-Type" to "application/json",
            ),
            body = body,
        )

    /** JSON-encode a string value (quotes + escapes) for embedding in a request body. */
    private fun jsonString(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()
}
