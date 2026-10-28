package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_SEARCH_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy

/**
 * Built-in availability is independent of CLI preapproval, which belongs to Heartbeat MCP servers and to an
 * available [ClaudeProviderTools] tool: it has no local effect, and a print-mode CLI cannot ask the user for it.
 */
internal data class ClaudeToolFlags(val native: Set<String> = emptySet(), val allowed: Set<String> = emptySet()) {
    init {
        require(native.all { name -> ClaudeNativeCatalog.any { it.name == name } })
        require(
            allowed.all {
                it == "mcp__${HOSTED_TOOLS_SERVER}__*" || it in ClaudeSearchTools.values ||
                    (it in ClaudeProviderTools && it in native)
            },
        )
    }

    fun arguments(): List<String> = buildList {
        add("--tools=" + (native - CLAUDE_MCP_GROUP).joinToString(","))
        if (allowed.isNotEmpty()) add("--allowedTools=" + allowed.joinToString(","))
        // --tools restricts built-ins only. Explicit denies also remove disabled MCP search declarations.
        val denied = ClaudeSearchTools.values - allowed
        if (denied.isNotEmpty()) add("--disallowedTools=" + denied.joinToString(","))
    }
}

/** Keeps the MCP policy group only for a captured opt-in outside protected or tool-free turns. */
internal fun ClaudeToolFlags.withMcpAccess(isAllowed: Boolean): ClaudeToolFlags =
    if (isAllowed) this else copy(native = native - CLAUDE_MCP_GROUP)

/** Turn-local flags. Legacy optional tools still require their own toggle as well as absence of an Off policy. */
internal fun claudeToolFlags(
    policy: ResolvedToolPolicy,
    search: Boolean,
    subagents: Boolean,
    providerSearch: Boolean,
    /** Provider search and child agents cannot carry action evidence; MCP search requires a scoped guard. */
    requiresActionAnalysis: Boolean = false,
): ClaudeToolFlags {
    val isProviderSearch = search && !requiresActionAnalysis && providerSearch && "web_search" !in policy.hostedDenied
    val native = policy.nativeOn.intersect(ClaudeNativeCatalog.filter { it.isGated }.map { it.name }.toSet()) +
        (if (subagents && !requiresActionAnalysis) ClaudeAgentTools - policy.nativeOff else emptySet()) +
        (
            if (isProviderSearch && "WebSearch" !in policy.nativeOff) {
                setOf("WebSearch")
            } else {
                emptySet()
            }
        )
    val available = native - policy.nativeOff
    val bridged = if (search) {
        ClaudeSearchTools.filterKeys { it !in policy.hostedDenied }.values.toSet()
    } else {
        emptySet()
    }
    return ClaudeToolFlags(available, available.intersect(ClaudeProviderTools) + bridged)
}

/** Recovers our already-rendered flags at the process boundary; builders never infer permissions from native names. */
internal fun claudeToolFlags(arguments: List<String>): ClaudeToolFlags = ClaudeToolFlags(
    arguments.flagNames("--tools="),
    arguments.flagNames("--allowedTools="),
)

private fun List<String>.flagNames(prefix: String): Set<String> = singleOrNull {
    it.startsWith(
        prefix,
    )
}?.removePrefix(prefix)?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

/** Native tools running at the provider; when available they are preapproved like the bridge search tools. */
internal val ClaudeProviderTools: Set<String> = setOf("WebSearch")

internal val ClaudeSearchTools = linkedMapOf(
    "web_search" to "mcp__${HOSTED_SEARCH_SERVER}__web_search",
    "web_fetch" to "mcp__${HOSTED_SEARCH_SERVER}__web_fetch",
)
