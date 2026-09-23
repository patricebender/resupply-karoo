package io.resupply.karoo.data

import kotlinx.serialization.Serializable

/**
 * The **durable** state of a region download — what must survive the app being killed, so a
 * download interrupted by process death or a crash can be resumed/retried on next launch.
 * Persisted in [ConfigStore]. The fast-changing bits (byte progress, live rate/ETA) are NOT
 * here — those live in the download service's in-memory flow, since persisting them every
 * tick would thrash DataStore for no benefit; only the coarse [Phase] is durable.
 */
@Serializable
data class RegionDownloadStatus(
    val regionId: String,
    val phase: Phase,
    /** Failure reason when [phase] is [Phase.FAILED], else null. */
    val reason: String? = null,
) {
    @Serializable
    enum class Phase {
        /** Queued/started but not finished — on restart this means "resume or retry". */
        ACTIVE,
        FAILED,
    }
}
