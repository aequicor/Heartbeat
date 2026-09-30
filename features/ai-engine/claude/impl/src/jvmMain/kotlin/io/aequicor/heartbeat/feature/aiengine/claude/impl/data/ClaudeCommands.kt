package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId

/**
 * No ambient settings, helpers, MCP or tool permissions are inherited by the adapter.
 * `--strict-mcp-config` without `--mcp-config` admits no servers; inline JSON is avoided because Windows
 * process creation does not preserve embedded quotes.
 */
internal fun claudeArguments(
    model: ModelId? = null,
    session: String? = null,
    resume: Boolean = false,
    search: Boolean = false,
    effort: String? = null,
): List<String> = buildList {
    addAll(listOf("--print", "--verbose", "--output-format", "stream-json", "--setting-sources="))
    addAll(listOf("--tools=", "--strict-mcp-config"))
    if (search) add(SEARCH_BRIDGE_MARKER)
    model?.let { add("--model=${it.value}") }
    // Passed per process: `--setting-sources=` hides any effort configured in settings files.
    effort?.let { add("--effort=$it") }
    session?.let { add(if (resume) "--resume=$it" else "--session-id=$it") }
}

/** Effort levels accepted by `claude --effort`; each model advertises its subset in `supportedEffortLevels`. */
internal val ClaudeEffortLevels = setOf("low", "medium", "high", "xhigh", "max")

internal const val SEARCH_BRIDGE_MARKER = "--heartbeat-search-bridge"
