package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/**
 * Hosted context of a turn: the tools of the session's project, or detached hosted tools of a chat without a project,
 * and the instructions describing them; blank [instructions] add nothing to the prompt.
 */
internal data class KoogWorkspace(val tools: List<KoogTool>, val instructions: String)

/**
 * Opens hosted tools of a turn: coding tools of a local project on Desktop only, detached hosted tools of a chat
 * without a project on every platform.
 */
internal fun interface KoogWorkspaces {
    /** Hosted workspace workflows may remain available while the coding toggle disables file and shell tools. */
    val hasHostedTools: Boolean get() = false

    /** Workspace of [ref], or null when the platform has no coding tools or the project is unavailable. */
    suspend fun open(ref: WorkspaceRef): KoogWorkspace?

    /**
     * Opens tools against the accepted native turn; [areSessionHooksEnabled] is the turn's hook opt-in (see
     * [koogToolScope]). Legacy fixtures retain their old opening contract.
     */
    suspend fun open(ref: WorkspaceRef, context: AgentToolContext, areSessionHooksEnabled: Boolean): KoogWorkspace? =
        open(ref)

    /** Detached hosted tools of a session without a project, opened against the accepted native turn. */
    suspend fun openDetached(context: AgentToolContext, areSessionHooksEnabled: Boolean): KoogWorkspace? = null

    /**
     * Instructions independent of tools (harness content) for a turn that sends no hosted tool; asked only for a
     * session that opted into hooks. Koog declares that it refreshes instructions every turn, so session hooks
     * leave this content to the system prompt.
     */
    suspend fun instructions(context: AgentToolContext): String = ""
}

/** Content of [tools] that needs none of its tools: instructions for an empty declared set. */
internal suspend fun independentKoogInstructions(tools: ProfileAgentTools, context: AgentToolContext): String =
    tools.instructions(context.koogToolScope(areSessionHooksEnabled = true).copy(declared = emptySet()))

/** Hosted tools that serve sessions without a project; available on every platform with the common dispatcher. */
internal suspend fun detachedKoogWorkspace(
    tools: ProfileAgentTools,
    context: AgentToolContext,
    areSessionHooksEnabled: Boolean,
): KoogWorkspace? {
    require(context.workspace == null)
    val scope = context.koogToolScope(areSessionHooksEnabled)
    val specs = tools.specifications(scope)
    if (specs.isEmpty()) return null
    val instructions = tools.instructions(scope.copy(declared = specs.mapTo(mutableSetOf()) { it.name }))
    return KoogWorkspace(koogHostedTools(specs, tools, context), instructions)
}

/** Keeps the coding toggle effective without hiding separately enabled hosted workspace workflows. */
internal fun KoogWorkspace.withCodingTools(isEnabled: Boolean): KoogWorkspace? {
    val available = if (isEnabled) tools else tools.filterNot { it.descriptor.name in CODING_TOOL_NAMES }
    return takeIf { available.isNotEmpty() }?.copy(tools = available)
}

private val CODING_TOOL_NAMES = setOf("read_file", "list_dir", "glob", "grep", "write_file", "edit_file", "run_command")

/**
 * Koog rebuilds declarations and system instructions for every accepted turn, yet reports it only for a session
 * that opted into hooks: per-turn content (harness context and its scripts) stands in for those hooks, so a
 * session without them (transfers, organic cells) gets the tool guide only, as on frozen engines.
 */
internal fun AgentToolContext.koogToolScope(areSessionHooksEnabled: Boolean): AgentToolScope =
    AgentToolScope(workspace, target, session = session, isRefreshedPerTurn = areSessionHooksEnabled)
