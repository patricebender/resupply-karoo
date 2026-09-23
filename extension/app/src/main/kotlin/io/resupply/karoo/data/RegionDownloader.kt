package io.resupply.karoo.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Downloads a region file with a **direct HTTP connection** (OkHttp), streamed to disk. This
 * bypasses the Karoo bridge's 100 KB-per-request cap entirely: a region is one GET at full
 * line speed instead of hundreds of ~96 KB Range chunks. The tradeoff is that a direct
 * connection only works on WiFi — off WiFi the only route is the phone's BLE bridge — so the
 * caller gates downloads on WiFi and advises the rider (see Connectivity / RegionsScreen).
 *
 * The download streams to a temp file, tracks a rolling throughput for a *measured* ETA
 * (never a pre-estimate), verifies sha256, gunzips, and hands the SQLite to
 * [PoiDatabase.installFromFile] (additive merge, dedup by osm_id, R*Tree rebuilt). An
 * interrupted download only ever leaves a temp file — the live DB is untouched until a fully
 * verified file installs.
 */
class RegionDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Live download progress: [done]/[total] compressed bytes, plus measured rate + ETA. */
    data class Progress(
        val done: Long,
        val total: Long,
        /** Rolling bytes/sec over the recent window; 0 until enough samples. */
        val bytesPerSec: Long,
    ) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        /** Seconds remaining from the current rate, or null when not yet estimable. */
        val etaSeconds: Long?
            get() = if (bytesPerSec > 0 && total > done) (total - done) / bytesPerSec else null
    }

    sealed interface Result {
        data class Installed(val poiCount: Int) : Result
        data class SchemaMismatch(val manifestVersion: Int, val appVersion: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Download [entry] from [manifest] over a direct connection and merge it into the live
     * DB. [scratchDir] is a writable temp dir (use `context.cacheDir`). [onProgress] fires as
     * bytes arrive. Blocking; call on an IO dispatcher (the service does).
     */
    fun downloadAndInstall(
        context: Context,
        manifest: RegionManifest,
        entry: RegionManifestEntry,
        scratchDir: File,
        onProgress: (Progress) -> Unit,
    ): Result {
        if (manifest.schemaVersion != PoiDatabase.BUNDLED_DB_VERSION) {
            return Result.SchemaMismatch(manifest.schemaVersion, PoiDatabase.BUNDLED_DB_VERSION)
        }

        val url = manifest.baseUrl.trimEnd('/') + "/" + entry.file
        // Download to a temp file; only a fully verified file is promoted to the install.
        val tmpGz = File(scratchDir, entry.file + ".part")
        tmpGz.delete()

        val ok = runCatching { streamTo(url, entry.bytesGz, tmpGz, onProgress) }
            .onFailure { Timber.e(it, "region ${entry.id} download failed") }
            .getOrDefault(false)
        if (!ok) {
            tmpGz.delete()
            return Result.Failed("download failed")
        }

        val actualSha = sha256Of(tmpGz)
        if (!actualSha.equals(entry.sha256, ignoreCase = true)) {
            Timber.e("sha256 mismatch for ${entry.file}: got $actualSha want ${entry.sha256}")
            tmpGz.delete()
            return Result.Failed("checksum mismatch")
        }

        val sqlite = File(scratchDir, entry.id + ".sqlite")
        val gunzipped = runCatching { gunzip(tmpGz, sqlite) }
            .onFailure { Timber.e(it, "gunzip failed") }.getOrDefault(false)
        tmpGz.delete()
        if (!gunzipped) {
            sqlite.delete()
            return Result.Failed("decompression failed")
        }

        val count = PoiDatabase.installFromFile(context, sqlite, entry.id)
            ?: return Result.Failed("region file rejected on install")
        return Result.Installed(count)
    }

    /**
     * Stream [url] into [dest], reporting progress with a rolling throughput. [expectedTotal]
     * (from the manifest) is the progress denominator, refined by the response's
     * `Content-Length` when present. Returns false on any non-2xx or a short read.
     */
    private fun streamTo(
        url: String,
        expectedTotal: Long,
        dest: File,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.e("download HTTP ${response.code} for $url")
                return false
            }
            val body = response.body ?: return false
            val total = body.contentLength().takeIf { it > 0 } ?: expectedTotal

            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    // Rolling window for the rate: bytes seen and the clock at the window start.
                    var windowStartMs = System.currentTimeMillis()
                    var windowStartBytes = 0L
                    var bytesPerSec = 0L
                    var lastEmitMs = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n

                        val now = System.currentTimeMillis()
                        // Recompute the rate roughly every second of wall time.
                        val windowMs = now - windowStartMs
                        if (windowMs >= 1_000) {
                            bytesPerSec = ((done - windowStartBytes) * 1_000) / windowMs
                            windowStartMs = now
                            windowStartBytes = done
                        }
                        // Emit at most ~5x/sec so the UI updates smoothly without churn.
                        if (now - lastEmitMs >= 200 || done == total) {
                            onProgress(Progress(done, total, bytesPerSec))
                            lastEmitMs = now
                        }
                    }
                    out.flush()
                    onProgress(Progress(done, total, bytesPerSec))
                    // A short read (server truncation) — the sha256 gate would catch it too,
                    // but bail early and cheaply.
                    if (total > 0 && done < total) {
                        Timber.e("short download: $done/$total bytes for $url")
                        return false
                    }
                }
            }
        }
        return dest.length() > 0
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun gunzip(src: File, dest: File): Boolean {
        GZIPInputStream(src.inputStream()).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest.length() > 0
    }
}
