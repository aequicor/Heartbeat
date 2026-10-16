package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A reserved subagent of a session: its scheduler action [id], chat [title] and the chosen model, persisted before
 * scheduler admission and kept until the session reads its result. The work and result belong to the scheduler.
 */
@Serializable
internal data class SubagentRun(
    val id: String,
    val parent: SessionRef,
    val title: String,
    val model: String,
    val effort: String? = null,
    val startedAt: Instant,
) {
    override fun toString(): String = "SubagentRun(id=$id)"
}

/** Profile storage of started subagents. */
internal interface SubagentRunStore {
    /** Runs not read yet, oldest first. */
    suspend fun list(): List<SubagentRun>

    /** Atomically reserves [run] before starting it; false when full or the id exists. Never evicts unread runs. */
    suspend fun reserve(run: SubagentRun): Boolean

    /** Forgets runs read by their parent. */
    suspend fun remove(ids: Set<String>)
}

/** Maximum number of unread or starting subagents retained by a profile. */
internal const val MAX_SUBAGENT_RUNS = 48
