package io.resupply.karoo.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import timber.log.Timber

/**
 * Disk cache of the last fetched region manifest, so the picker renders its full, up-to-date
 * Browse list instantly on a cold start — before (or without) a network fetch — superseding the
 * bundled [RegionCatalog] fallback seed. Written whenever a manifest fetch succeeds; read once at
 * startup. One file under `filesDir`, loaded/saved through `runCatching` like [RoadbookCache] and
 * [ResupplyRepository]'s place-id cache.
 */
object RegionManifestCache {
    private const val FILE_NAME = "resupply_region_manifest.json"
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /** The cached manifest, or null if none is stored yet / it can't be read. */
    fun load(context: Context): RegionManifest? =
        runCatching {
            file(context).takeIf { it.exists() }
                ?.let { json.decodeFromString<RegionManifest>(it.readText()) }
        }.onFailure { Timber.w(it, "failed to read cached region manifest") }.getOrNull()

    /**
     * Persist [manifest] so the next cold start renders from it. Best-effort, and **atomic**:
     * write to a temp file then rename over the target, so a process kill mid-write can never
     * leave a half-written file that [load] would reject (→ the rider silently dropping back to
     * the smaller bundled seed). The previous good cache stays intact until the rename lands.
     */
    fun save(context: Context, manifest: RegionManifest) {
        runCatching {
            val target = file(context)
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(RegionManifest.serializer(), manifest))
            if (!tmp.renameTo(target)) {
                // Rename can fail if the target exists on some filesystems; overwrite then retry.
                target.delete()
                if (!tmp.renameTo(target)) error("rename failed for $target")
            }
        }.onFailure { Timber.w(it, "failed to persist region manifest cache") }
    }
}
