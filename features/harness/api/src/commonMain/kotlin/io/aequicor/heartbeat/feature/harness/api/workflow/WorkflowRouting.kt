package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import kotlinx.serialization.Serializable

/**
 * Immutable host-captured route and request ancestry, persisted before workflow admission. Handoff contains
 * restrictions, never approval. Tool arguments and author code cannot supply it. Parentless workflows use
 * [workspace]; helpers with a parent inherit that parent's durable route. Legacy missing routing fails closed
 * before any helper prompt rather than reconstructing ancestry from text or the current library revision.
 */
@Serializable
public data class WorkflowRouting(
    val workspace: WorkspaceRef?,
    val handoff: HelperHandoff,
    val libraryEpoch: String = "",
    val libraryGeneration: Long = 0,
) {
    init {
        require(libraryGeneration >= 0)
    }
    override fun toString(): String = "WorkflowRouting(***)"
}
