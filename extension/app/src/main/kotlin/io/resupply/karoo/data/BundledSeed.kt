package io.resupply.karoo.data

import io.resupply.karoo.BuildConfig

/**
 * The POI data an edition ships pre-bundled in its APK — the single source of truth for
 * "what's installed out of the box". Fed by per-flavor [BuildConfig] fields so the same
 * code produces every edition:
 *   - **lean** — nothing bundled ([asset] empty); the rider downloads every region.
 *   - **usa** — the merged `usa` Complete seed; [regionIds] = {usa}, which covers all
 *     `usa-<state>` rows via [Region.covers].
 *   - **coreEurope** — a merged seed of several countries; [regionIds] lists each so the
 *     picker shows them individually as installed.
 *
 * [regionIds] is what the app persists as installed on first run (see the startup reconcile
 * in MainActivity), so the on-device installed set always matches the DB the seed produced.
 */
object BundledSeed {
    /** Bundled seed asset filename in `assets/`, or "" for the lean edition. */
    val asset: String get() = BuildConfig.SEED_ASSET

    /** Region ids the bundled seed provides (empty for lean). */
    val regionIds: Set<String>
        get() = BuildConfig.SEED_REGION_IDS
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** True for the lean edition: no bundled data, everything downloaded on demand. */
    val isLean: Boolean get() = asset.isEmpty()
}
