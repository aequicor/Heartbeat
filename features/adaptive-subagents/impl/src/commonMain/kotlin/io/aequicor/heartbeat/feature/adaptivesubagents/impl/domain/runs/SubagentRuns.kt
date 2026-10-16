package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A reserved subagent of a session: its scheduler action [id], chat [title] and the chosen model, persisted before
 * scheduler admission and kept until the session reads its result. The scheduler owns the work; [result] backs up
 * its final answer while transferring retention to the tool, so a failed acknowledgement can be retried.
 */
@Serializable
internal data class SubagentRun(
    val id: String,
    val parent: SessionRef,
    val title: String,
    val model: String,
    val effort: String? = null,
    val startedAt: Instant,
    val result: SubagentResult? = null,
) {
    override fun toString(): String = "SubagentRun(id=$id)"
}

/** Durable handoff backup, not an exactly-once guarantee across a crash or transport failure after tool delivery. */
@Serializable
internal data class SubagentResult(val isCompleted: Boolean, val text: String) {
    override fun toString(): String = "SubagentResult(isCompleted=$isCompleted)"
}

/** Profile storage of started subagents. */
internal interface SubagentRunStore {
    /** Runs not read yet, oldest first. */
    suspend fun list(): List<SubagentRun>

    /** Atomically reserves [run] before starting it; false when full or the id exists. Never evicts unread runs. */
    suspend fun reserve(run: SubagentRun): Boolean

    /** Atomically backs up all [results] before scheduler acknowledgement; every id must already be reserved. */
    suspend fun saveResults(results: Map<String, SubagentResult>)

    /** Forgets runs read by their parent. */
    suspend fun remove(ids: Set<String>)
}

/** Maximum number of unread or starting subagents retained by a profile. */
internal const val MAX_SUBAGENT_RUNS = 48
