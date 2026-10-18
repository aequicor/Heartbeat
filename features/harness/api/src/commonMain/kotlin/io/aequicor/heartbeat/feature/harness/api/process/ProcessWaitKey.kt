package io.aequicor.heartbeat.feature.harness.api.process

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Snapshot checked before resuming a durable wait. Capture it before querying the external system, then submit
 * [ProcessCommand.ResumeChecked] with the same key. A changed input, subject, lifetime or wait invalidates that
 * observation. The harness must first persist the input revision and SHA it observed; this comparison cannot
 * discover a remote change that has not been recorded locally. This key owns no executor or workflow deadline.
 */
@Serializable
public data class ProcessWaitKey(
    val process: ProcessId,
    val incarnation: String,
    val wait: Int,
    val inputRevision: Long,
    val subject: SubjectSha?,
    val definition: ProcessDefinition? = null,
) {
    init {
        require(isPlainToken(incarnation))
        require(wait >= 1 && inputRevision >= 0)
    }

    override fun toString(): String = "ProcessWaitKey(***)"
}

/** The exact wait to reconcile, or null when this process is not waiting. */
public fun ProcessRecord.waitKey(): ProcessWaitKey? = (phase as? ProcessPhase.Waiting)?.wait?.let {
    ProcessWaitKey(id, incarnation, it.number, inputRevision, subject, definition)
}

internal fun ProcessRecord.resumeChecked(key: ProcessWaitKey, now: Instant): ProcessReduction = when {
    key.process != id || key.incarnation != incarnation -> rejected(ProcessRejection.UnknownWait)

    key.wait < nextWait - 1 || (key.wait == nextWait - 1 && phase !is ProcessPhase.Waiting) ->
        ProcessReduction.Unchanged(this)

    key.wait >= nextWait -> rejected(ProcessRejection.UnknownWait)

    key.definition != definition -> rejected(ProcessRejection.DefinitionMismatch)

    key.inputRevision != inputRevision || key.subject != subject ->
        rejected(ProcessRejection.StaleRevision)

    else -> changed(copy(phase = ProcessPhase.Idle, updatedAt = now))
}
