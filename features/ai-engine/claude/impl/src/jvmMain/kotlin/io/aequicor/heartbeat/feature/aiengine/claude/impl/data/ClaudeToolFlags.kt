package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy

/** Built-in availability is independent of CLI preapproval, which belongs exclusively to Heartbeat MCP servers. */
internal data class ClaudeToolFlags(val native: Set<String> = emptySet(), val allowed: Set<String> = emptySet()) {
    init {
        require(native.all { name -> ClaudeNativeCatalog.any { it.name == name } })
        require(allowed.all { it == "mcp__${HOSTED_TOOLS_SERVER}__*" || it in ClaudeSearchTools.values })
    }

    fun arguments(): List<String> = buildList {
        add("--tools=" + native.joinToString(","))
        if (allowed.isNotEmpty()) add("--allowedTools=" + allowed.joinToString(","))
        // --tools restricts built-ins only. Explicit denies also remove disabled MCP search declarations.
        val denied = ClaudeSearchTools.values - allowed
        if (denied.isNotEmpty()) add("--disallowedTools=" + denied.joinToString(","))
    }
}

/** Turn-local flags. Legacy optional tools still require their own toggle as well as absence of an Off policy. */
internal fun claudeToolFlags(
    policy: ResolvedToolPolicy,
    search: Boolean,
    subagents: Boolean,
    providerSearch: Boolean,
): ClaudeToolFlags {
    val isProviderSearch = search && providerSearch && "web_search" !in policy.hostedDenied
    val native = policy.nativeOn.intersect(ClaudeNativeCatalog.filter { it.isGated }.map { it.name }.toSet()) +
        (if (subagents) ClaudeAgentTools - policy.nativeOff else emptySet()) +
        (
            if (isProviderSearch && "WebSearch" !in policy.nativeOff) {
                setOf("WebSearch")
            } else {
                emptySet()
            }
        )
    val allowed = if (search) ClaudeSearchTools.filterKeys { it !in policy.hostedDenied }.values.toSet() else emptySet()
    return ClaudeToolFlags(native - policy.nativeOff, allowed)
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

internal val ClaudeSearchTools = linkedMapOf(
    "web_search" to "mcp__heartbeat_search__web_search",
    "web_fetch" to "mcp__heartbeat_search__web_fetch",
)
