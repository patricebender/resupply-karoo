package io.resupply.karoo.build

import io.resupply.karoo.data.Category

/** Observable state of the roadbook build, driving the UI. */
sealed interface BuildState {
    data object Idle : BuildState

    /** In progress (querying the local database). */
    data class Building(val phase: String = "Searching…") : BuildState

    /** Completed: total plus a per-category breakdown for glanceable feedback. */
    data class Success(
        val count: Int,
        val byCategory: Map<Category, Int>,
        val atEpochMs: Long,
    ) : BuildState

    /**
     * [regionMissing] is true only when a route corridor query ran and found nothing because no
     * installed region covers it — the rider should download one. False for connectivity failures,
     * GPS timeouts, and other hard errors that aren't solved by downloading a region.
     */
    data class Error(val message: String, val regionMissing: Boolean = false) : BuildState
}
