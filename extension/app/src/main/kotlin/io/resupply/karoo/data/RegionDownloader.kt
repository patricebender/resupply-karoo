package io.resupply.karoo.data

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.resupply.karoo.util.httpRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Downloads a region file and merges it into the live DB. The transfer is **hybrid**: on WiFi
 * a direct OkHttp connection streams the whole file at line speed; off WiFi the same download
 * runs over the Karoo's phone bridge in ~96 KB Range chunks (slower, but works on-the-go).
 * The caller picks the [Transport] by current connectivity (see RegionDownloadService); this
 * class owns everything downstream of "get the compressed bytes" so both paths share it.
 *
 * The compressed bytes flow through one shared pipeline regardless of transport:
 *   transport → [DigestOutputStream] (SHA-256 over compressed bytes) → gunzip pipe → SQLite.
 * No intermediate `.gz` file is written. The hash is checked once the stream ends and the
 * SQLite is deleted on mismatch. The live DB is untouched until a fully verified file installs.
 */
class RegionDownloader {

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
     * The swappable byte source. A transport streams the region's **compressed** bytes into
     * [sink] (which the downloader has wired to hash + gunzip), reporting compressed-byte
     * progress via [onProgress] as they arrive. Returns false on any non-2xx, short read, or
     * transfer error. It must not close [sink], the downloader owns its lifecycle.
     */
    interface Transport {
        fun fetch(
            url: String,
            /** Compressed-byte denominator for progress (manifest `bytesGz`); refined by the transport if it learns better. */
            expectedTotal: Long,
            sink: OutputStream,
            onProgress: (Progress) -> Unit,
        ): Boolean
    }

    /**
     * Download [entry] from [manifest] via [transport] and merge it into the live DB.
     * [scratchDir] is a writable temp dir (use `context.cacheDir`). [onProgress] fires as
     * bytes arrive. Blocking; call on an IO dispatcher (the service does).
     */
    fun downloadAndInstall(
        context: Context,
        manifest: RegionManifest,
        entry: RegionManifestEntry,
        transport: Transport,
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

        // Wire the shared pipeline: the transport writes compressed bytes into `digesting`,
        // which updates the SHA-256 and forwards to `gunzip`, which decompresses to the SQLite.
        // A background thread runs the gunzip pull side of the pipe so the transport can push.
        val md = MessageDigest.getInstance("SHA-256")
        val t0 = System.currentTimeMillis()
        val ok = runCatching { streamThroughPipeline(url, entry.bytesGz, transport, md, sqlite, onProgress) }
            .onFailure { Timber.e(it, "region ${entry.id} download/decompress failed") }
            .getOrDefault(false)
        if (!ok) {
            sqlite.delete()
            return Result.Failed("download failed")
        }
        Timber.d("region ${entry.id}: stream+decompress done in ${System.currentTimeMillis() - t0} ms, sqlite=${sqlite.length() / 1024} KB")

        // Bytes are down and decompressed; verify the hash of the compressed stream.
        onInstalling()

        val actualSha = md.digest().joinToString("") { "%02x".format(it) }
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
     * Run the compressed stream from [transport] through SHA-256 ([digest]) and GZIP into
     * [dest]. A [java.io.PipedInputStream]/[java.io.PipedOutputStream] pair bridges the
     * transport's push (writing compressed bytes) to the gunzip's pull (reading them): the
     * transport writes into a [DigestOutputStream] over the pipe on a worker thread, while
     * this thread reads decompressed bytes off the pipe and writes the SQLite. Returns false
     * if the transport reports failure or nothing was written.
     */
    private fun streamThroughPipeline(
        url: String,
        expectedTotal: Long,
        transport: Transport,
        digest: MessageDigest,
        dest: File,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val pipeIn = java.io.PipedInputStream(64 * 1024)
        val pipeOut = java.io.PipedOutputStream(pipeIn)

        // Producer: transport → digest → pipe. Runs on its own thread so this thread can pull
        // the gunzip side concurrently. Result is captured for the join below.
        var producerOk = false
        var producerError: Throwable? = null
        val producer = Thread {
            try {
                DigestOutputStream(pipeOut, digest).use { digesting ->
                    producerOk = transport.fetch(url, expectedTotal, digesting, onProgress)
                }
            } catch (t: Throwable) {
                producerError = t
            } finally {
                // Closing the write end unblocks the reader's terminal read even if the
                // transport bailed early.
                runCatching { pipeOut.close() }
            }
        }.apply { name = "region-download"; start() }

        // Consumer: pipe → gunzip → SQLite, on this thread.
        val consumerOk = runCatching {
            GZIPInputStream(pipeIn).use { decompressed ->
                dest.outputStream().use { out -> decompressed.copyTo(out, 64 * 1024) }
            }
            dest.length() > 0
        }.onFailure { Timber.e(it, "gunzip pipeline failed for $url") }.getOrDefault(false)

        producer.join()
        producerError?.let { Timber.e(it, "transport thread failed for $url") }
        return producerOk && consumerOk
    }

    /**
     * Fast path (WiFi): one direct OkHttp GET, streamed. Bypasses the bridge's 100 KB cap
     * entirely, a region is one request at full line speed. Only reaches the internet over
     * WiFi, so the service selects it only when on WiFi.
     */
    class DirectTransport : Transport {
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        override fun fetch(
            url: String,
            expectedTotal: Long,
            sink: OutputStream,
            onProgress: (Progress) -> Unit,
        ): Boolean {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("download HTTP ${response.code} for $url")
                    return false
                }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: expectedTotal
                val rate = RateMeter(total)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        rate.advance(n.toLong(), onProgress)
                    }
                }
                rate.finish(onProgress)
            }
            return true
        }
    }

    /**
     * Fallback path (off WiFi): ranged GETs over the Karoo phone bridge. The bridge delivers
     * each response as a whole `ByteArray` and caps responses at ~100 KB, over that it drops
     * the response with `RESPONSE_TOO_LARGE` and the request hangs. So a region is downloaded
     * in [CHUNK_BYTES] Range windows, each safely under the cap; that's also the progress
     * signal (chunk N of M). Each bridged request carries a fixed ~2.4 s overhead independent
     * of payload, so this is request-count-bound and much slower than [DirectTransport], but
     * it works with no WiFi.
     */
    class BridgeTransport(private val system: KarooSystemService) : Transport {

        override fun fetch(
            url: String,
            expectedTotal: Long,
            sink: OutputStream,
            onProgress: (Progress) -> Unit,
        ): Boolean {
            val startAll = System.currentTimeMillis()
            var chunks = 0
            var offset = 0L
            var total = expectedTotal
            val rate = RateMeter(total)
            while (offset < total) {
                val end = minOf(offset + CHUNK_BYTES - 1, total - 1)
                // fetch() runs on the pipeline's worker thread (not the main coroutine), so
                // blocking per chunk here is fine and keeps the transport contract synchronous.
                val complete = kotlinx.coroutines.runBlocking {
                    system.httpRequest(method = "GET", url = url, headers = mapOf("Range" to "bytes=$offset-$end"))
                } ?: return false
                val body = complete.body ?: return false
                chunks++

                when (complete.statusCode) {
                    206 -> {
                        contentRangeTotal(complete.headers)?.let { total = it; rate.total = it }
                        val requested = (end - offset + 1).toInt()
                        if (body.isEmpty() || (body.size < requested && offset + body.size < total)) {
                            Timber.e("short range chunk: got ${body.size}B, wanted $requested")
                            return false
                        }
                        sink.write(body)
                        offset += body.size
                    }
                    200 -> {
                        // Server ignored Range and sent the whole file. Only trust it if it's
                        // actually the whole file; a capped/short 200 is a truncation.
                        if (body.size.toLong() < total) {
                            Timber.e("200 without Range: ${body.size}B < $total; bridge truncation")
                            return false
                        }
                        sink.write(body)
                        offset = body.size.toLong()
                        total = offset
                    }
                    else -> {
                        Timber.d("range GET failed: ${complete.statusCode} ${complete.error}")
                        return false
                    }
                }
                rate.set(offset, onProgress)
            }
            Timber.d("bridge download done: $offset B in $chunks chunk(s), ${System.currentTimeMillis() - startAll}ms")
            rate.finish(onProgress)
            return true
        }

        /** Parse the total length out of a `Content-Range: bytes 0-1023/12345` header. */
        private fun contentRangeTotal(headers: Map<String, String>): Long? {
            val v = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value
            return v?.substringAfterLast('/')?.trim()?.toLongOrNull()
        }
    }

    /**
     * Measures throughput and throttles progress emissions, shared by both transports so the
     * picker's live rate/ETA row works regardless of path. [total] is mutable because the
     * bridge learns the real total from `Content-Range` mid-flight.
     */
    private class RateMeter(var total: Long) {
        private var done = 0L
        private var windowStartMs = System.currentTimeMillis()
        private var windowStartBytes = 0L
        private var bytesPerSec = 0L
        private var lastEmitMs = 0L

        /** Direct path: advance by [delta] bytes just written. */
        fun advance(delta: Long, onProgress: (Progress) -> Unit) = set(done + delta, onProgress)

        /** Bridge path: set the absolute compressed-bytes-done count. */
        fun set(newDone: Long, onProgress: (Progress) -> Unit) {
            done = newDone
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

        fun finish(onProgress: (Progress) -> Unit) = onProgress(Progress(total, total, bytesPerSec))
    }

    companion object {
        /**
         * Per-request Range chunk for the bridge path. The Karoo HTTP bridge caps responses at
         * ~100 KB (System Service enforced, even on WiFi); over that it drops the response with
         * `RESPONSE_TOO_LARGE` and the request hangs. 96 KB leaves ~4 KB headroom for response
         * headers. A ~50 MB region is then ~530 sequential GETs.
         */
        private const val CHUNK_BYTES = 96L * 1024
    }
}
