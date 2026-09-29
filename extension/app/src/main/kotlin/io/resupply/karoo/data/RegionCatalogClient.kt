package io.resupply.karoo.data

import io.hammerhead.karooext.KarooSystemService
import io.resupply.karoo.util.httpRequest
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Fetches the region manifest over the Karoo HTTP bridge (like [WikipediaClient]/[PlacesClient])
 * so it works over the paired phone. The actual region file download lives in [RegionDownloader],
 * which picks a direct (WiFi) or bridged transport by connectivity; this class only supplies the
 * catalog the picker and downloader read from.
 */
class RegionCatalogClient(private val system: KarooSystemService) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch and parse the manifest. Returns null on any network/parse failure (caller
     * shows an offline/error state). Does not enforce the schema version here, that's
     * checked at download time so a mismatch can still list what's available.
     *
     * [failFast] shortens the wait for the picker's on-open load so a dead state (no WiFi, no
     * phone) resolves in ~12 s instead of the full 30 s bridge timeout. It still keeps
     * `waitForConnection = true`, so a connected or just-waking bridge is honored, we only cap
     * how long we'll wait. A download leaves it patient (default): the rider asked for it, so
     * give the bridge the full timeout to come up.
     */
    suspend fun fetchManifest(failFast: Boolean = false): RegionManifest? {
        val complete = if (failFast) {
            system.httpRequest(method = "GET", url = MANIFEST_URL, timeoutMs = 12_000)
        } else {
            system.httpRequest(method = "GET", url = MANIFEST_URL)
        } ?: return null
        val body = complete.body
        if (complete.statusCode !in 200..299 || body == null) {
            Timber.d("manifest fetch failed: ${complete.statusCode} ${complete.error}")
            return null
        }
        return runCatching {
            json.decodeFromString<RegionManifest>(body.decodeToString())
        }.onFailure { Timber.e(it, "manifest parse failed") }.getOrNull()
    }

    companion object {
        /** Stable manifest URL (a pinned `regions-latest` release asset). */
        const val MANIFEST_URL =
            "https://github.com/patricebender/resupply-karoo/releases/download/regions-latest/manifest.json"
    }
}
