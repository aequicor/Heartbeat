package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy

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
    tools: ClaudeToolFlags = claudeToolFlags(ResolvedToolPolicy(), search, subagents = false, providerSearch = true),
): List<String> = buildList {
    addAll(listOf("--print", "--verbose", "--output-format", "stream-json", "--setting-sources="))
    add("--strict-mcp-config")
    addAll(tools.arguments())
    if (search) add(SEARCH_BRIDGE_MARKER)
    model?.let { add("--model=${it.value}") }
    // Passed per process: `--setting-sources=` hides any effort configured in settings files.
    effort?.let { add("--effort=$it") }
    session?.let { add(if (resume) "--resume=$it" else "--session-id=$it") }
}

/** Effort levels accepted by `claude --effort`; each model advertises its subset in `supportedEffortLevels`. */
internal val ClaudeEffortLevels = setOf("low", "medium", "high", "xhigh", "max")

internal const val SEARCH_BRIDGE_MARKER = "--heartbeat-search-bridge"

/** A verified gate plan or a legacy turn with all additional native tools removed. */
internal data class ClaudeTurnPlan(
    val arguments: List<String>,
    val native: ClaudeNativeWorkspace?,
    val gated: Set<String>,
)

/** Existing multimodal submissions are already user JSON frames; plain text is never reparsed as protocol. */
internal fun claudeUserFrame(
    request: io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest,
    text: String,
): kotlinx.serialization.json.JsonObject = if (request.parts.any {
        it !is io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart.Text
    }
) {
    parseClaudeObject(text)
} else {
    kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("user"))
        put(
            "message",
            kotlinx.serialization.json.buildJsonObject {
                put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                put("content", kotlinx.serialization.json.JsonPrimitive(text))
            },
        )
        put("parent_tool_use_id", kotlinx.serialization.json.JsonNull)
    }
}
