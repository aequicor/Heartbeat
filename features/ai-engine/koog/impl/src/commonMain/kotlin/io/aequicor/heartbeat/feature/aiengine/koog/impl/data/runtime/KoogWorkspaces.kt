package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Coding context of a session opened on a local project: its tools and the instructions describing them. */
internal data class KoogWorkspace(val tools: List<KoogTool>, val instructions: String)

/** Opens coding workspaces; Desktop only, other platforms keep the plain chat. */
internal fun interface KoogWorkspaces {
    /** Hosted tools enforce the common trust gate instead of the legacy Koog coding toggle. */
    val hasHostedTools: Boolean get() = false

    /** Workspace of [ref], or null when the platform has no coding tools or the project is unavailable. */
    suspend fun open(ref: WorkspaceRef): KoogWorkspace?

    /** Opens tools against the accepted native turn. Legacy fixtures retain their old opening contract. */
    suspend fun open(ref: WorkspaceRef, context: AgentToolContext): KoogWorkspace? = open(ref)
}
