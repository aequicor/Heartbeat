package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId

/**
 * No ambient settings, helpers, MCP or tool permissions are inherited by this text-only adapter.
 * `--strict-mcp-config` without `--mcp-config` admits no servers; inline JSON is avoided because Windows
 * process creation does not preserve embedded quotes.
 */
internal fun claudeArguments(
    model: ModelId? = null,
    session: String? = null,
    resume: Boolean = false,
    search: Boolean = false,
): List<String> = buildList {
    addAll(listOf("--print", "--verbose", "--output-format", "stream-json", "--setting-sources="))
    addAll(listOf("--tools=", "--strict-mcp-config"))
    if (search) add(SEARCH_BRIDGE_MARKER)
    model?.let { add("--model=${it.value}") }
    session?.let { add(if (resume) "--resume=$it" else "--session-id=$it") }
}

internal const val SEARCH_BRIDGE_MARKER = "--heartbeat-search-bridge"
