package io.resupply.karoo.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.DigestInputStream
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
 * The download streams directly through SHA-256 verification and GZIP decompression in one
 * pass: the response body feeds a [DigestInputStream] (hashing the compressed bytes on the
 * fly) whose output is piped into [GZIPInputStream] and written as the final SQLite. No
 * intermediate `.gz` file is written; the hash is checked after the stream ends and the
 * SQLite is deleted on mismatch. The live DB is untouched until a fully verified file installs.
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
        /** Fires once streaming is done and the (verify → DB merge) install begins. */
        onInstalling: () -> Unit,
        /** Fires repeatedly during the DB merge with a 0–1 fraction. */
        onInstallProgress: (fraction: Float) -> Unit = {},
    ): Result {
        if (manifest.schemaVersion != PoiDatabase.BUNDLED_DB_VERSION) {
            return Result.SchemaMismatch(manifest.schemaVersion, PoiDatabase.BUNDLED_DB_VERSION)
        }

        val url = manifest.baseUrl.trimEnd('/') + "/" + entry.file
        val sqlite = File(scratchDir, entry.id + ".sqlite")
        sqlite.delete()

        // Stream: response body → DigestInputStream (SHA-256 over compressed bytes)
        //                       → GZIPInputStream → SQLite file.
        // No .gz temp file; the hash is checked once streaming is done.
        val md = MessageDigest.getInstance("SHA-256")
        val t0 = System.currentTimeMillis()
        val ok = runCatching { streamAndDecompress(url, entry.bytesGz, md, sqlite, onProgress) }
            .onFailure { Timber.e(it, "region ${entry.id} download/decompress failed") }
            .getOrDefault(false)
        if (!ok) {
            sqlite.delete()
            return Result.Failed("download failed")
        }
        Timber.d("region ${entry.id}: stream+decompress done in ${System.currentTimeMillis() - t0} ms, sqlite=${sqlite.length() / 1024} KB")

        // Bytes are down and decompressed; verify the hash of the compressed stream.
        onInstalling()

        val tHash = System.currentTimeMillis()
        val actualSha = md.digest().joinToString("") { "%02x".format(it) }
        Timber.d("region ${entry.id}: sha256 finalize in ${System.currentTimeMillis() - tHash} ms")
        if (!actualSha.equals(entry.sha256, ignoreCase = true)) {
            Timber.e("sha256 mismatch for ${entry.file}: got $actualSha want ${entry.sha256}")
            sqlite.delete()
            return Result.Failed("checksum mismatch")
        }

        val count = PoiDatabase.installFromFile(context, sqlite, entry.id, entry.dataVersion, onInstallProgress)
            ?: return Result.Failed("region file rejected on install")
        return Result.Installed(count)
    }

    /**
     * Stream [url] into [dest] after decompressing GZIP, feeding compressed bytes through
     * [digest] on the fly. [expectedTotal] is the compressed-byte denominator for progress
     * (refined by `Content-Length` when present). Returns false on any non-2xx, short read,
     * or decompression error.
     */
    private fun streamAndDecompress(
        url: String,
        expectedTotal: Long,
        digest: MessageDigest,
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

            // CountingInputStream sits between the raw body and the digest so we can report
            // compressed-byte progress (same unit as [total]) while the digest sees every byte.
            val counter = CountingInputStream(body.byteStream())
            DigestInputStream(counter, digest).use { digested ->
                GZIPInputStream(digested).use { decompressed ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var windowStartMs = System.currentTimeMillis()
                        var windowStartBytes = 0L
                        var bytesPerSec = 0L
                        var lastEmitMs = 0L
                        while (true) {
                            val n = decompressed.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)

                            val done = counter.count
                            val now = System.currentTimeMillis()
                            val windowMs = now - windowStartMs
                            if (windowMs >= 1_000) {
                                bytesPerSec = ((done - windowStartBytes) * 1_000) / windowMs
                                windowStartMs = now
                                windowStartBytes = done
                            }
                            if (now - lastEmitMs >= 200) {
                                onProgress(Progress(done.coerceAtMost(total), total, bytesPerSec))
                                lastEmitMs = now
                            }
                        }
                        out.flush()
                        onProgress(Progress(total, total, bytesPerSec))
                    }
                }
            }
        }
        return dest.length() > 0
    }

    /** Wraps an [InputStream] and counts compressed bytes read — for progress reporting. */
    private class CountingInputStream(private val src: java.io.InputStream) : java.io.InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int = src.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            src.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = src.close()
    }
}
