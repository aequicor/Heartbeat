package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Coding context of a session opened on a local project: its tools and the instructions describing them. */
internal data class KoogWorkspace(val tools: List<KoogTool>, val instructions: String)

/** Opens coding workspaces; Desktop only, other platforms keep the plain chat. */
internal fun interface KoogWorkspaces {
    /** Hosted workspace workflows may remain available while the coding toggle disables file and shell tools. */
    val hasHostedTools: Boolean get() = false

    /** Workspace of [ref], or null when the platform has no coding tools or the project is unavailable. */
    suspend fun open(ref: WorkspaceRef): KoogWorkspace?

    /** Opens tools against the accepted native turn. Legacy fixtures retain their old opening contract. */
    suspend fun open(ref: WorkspaceRef, context: AgentToolContext): KoogWorkspace? = open(ref)
}

/** Keeps the coding toggle effective without hiding separately enabled hosted workspace workflows. */
internal fun KoogWorkspace.withCodingTools(isEnabled: Boolean): KoogWorkspace? {
    val available = if (isEnabled) tools else tools.filterNot { it.descriptor.name in CODING_TOOL_NAMES }
    return takeIf { available.isNotEmpty() }?.copy(tools = available)
}

private val CODING_TOOL_NAMES = setOf("read_file", "list_dir", "glob", "grep", "write_file", "edit_file", "run_command")
