package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A subagent a session started: its scheduler action [id], chat [title] and the chosen model, kept until the session
 * reads its result. The work, recovery and result belong to the scheduler action.
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

    /** Adds [run], dropping the oldest beyond the limit. */
    suspend fun add(run: SubagentRun)

    /** Forgets runs read by their parent. */
    suspend fun remove(ids: Set<String>)
}
