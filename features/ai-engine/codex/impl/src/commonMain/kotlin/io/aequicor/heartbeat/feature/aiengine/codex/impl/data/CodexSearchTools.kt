package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolImage
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** App-server dynamic tools keep Querit credentials in the Heartbeat profile. */
private val searchLog = Log.tag("CodexSearchTool")

internal fun searchToolSpecs(): JsonArray = JsonArray(
    listOf(
        toolSpec(
            "web_search",
            "Find web sources for a query and return citation URLs.",
            mapOf(
                "query" to "string",
                "count" to "integer",
            ),
            listOf("query"),
        ),
        toolSpec("web_fetch", "Read the text content of one web URL.", mapOf("url" to "string"), listOf("url")),
    ),
)

private fun toolSpec(
    name: String,
    description: String,
    fields: Map<String, String>,
    required: List<String>,
): JsonObject = buildJsonObject {
    put("type", "function")
    put("name", name)
    put("description", description)
    put(
        "inputSchema",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    fields.forEach { (field, type) -> put(field, buildJsonObject { put("type", type) }) }
                },
            )
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        },
    )
}

internal suspend fun executeSearchTool(search: SearchEngine, tool: String, arguments: JsonElement): AgentToolResult {
    val args = arguments as? JsonObject ?: return AgentToolResult("InvalidInput", isError = true)
    return try {
        val output = when (tool) {
            "web_search" -> {
                val query = (args["query"] as? JsonPrimitive)?.content.orEmpty()
                val count = ((args["count"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)
                val results = search.search(query, count)
                JsonArray(
                    results.map { result ->
                        buildJsonObject {
                            put("url", result.url)
                            put("title", result.title)
                            put("snippet", result.snippet)
                        }
                    },
                )
            }

            "web_fetch" -> {
                val url = (args["url"] as? JsonPrimitive)?.content.orEmpty()
                val result = search.fetch(url)
                buildJsonObject {
                    put("url", result.url)
                    put("title", result.title)
                    put("content", result.text)
                }
            }

            else -> return AgentToolResult("InvalidInput", isError = true)
        }
        AgentToolResult(output.toString())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SearchException) {
        searchLog.w(e) { "Search tool failed" }
        AgentToolResult(e.failure.name, isError = true)
    } catch (e: Exception) {
        searchLog.w(e) { "Search tool failed" }
        AgentToolResult("Unavailable", isError = true)
    }
}

private fun toolFailure(reason: String): JsonObject = toolResult(false, reason)

/** Failed dynamic tool result that carries only a safe reason code. */
internal fun toolFailureResult(reason: String): JsonObject = toolFailure(reason)

private const val DEFAULT_COUNT = 5
private const val MAX_COUNT = 20

internal fun toolResult(success: Boolean, text: String, images: List<AgentToolImage> = emptyList()): JsonObject =
    buildJsonObject {
        put("success", success)
        put(
            "contentItems",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "inputText")
                        put("text", text)
                    },
                ) + images.map { image ->
                    buildJsonObject {
                        put("type", "inputImage")
                        put("imageUrl", image.dataUrl)
                    }
                },
            ),
        )
    }

/** Adapter-operated search passes the same policy, hook and permission gate as other hosted tools. */
internal suspend fun executeHostedSearch(
    search: SearchEngine,
    tools: ProfileAgentTools,
    context: AgentToolContext,
    name: String,
    arguments: JsonElement,
): JsonObject {
    val args = arguments as? JsonObject ?: return toolFailureResult("InvalidInput")
    return try {
        when (val verdict = tools.authorizeHosted(context, name, args)) {
            is NativeVerdict.Deny -> toolResult(false, verdict.reason)

            NativeVerdict.Allow -> {
                currentCoroutineContext().ensureActive()
                val result = executeSearchTool(search, name, args)
                val note = searchResultNote(tools, context, name, args, result)
                currentCoroutineContext().ensureActive()
                val text = listOfNotNull(note?.take(MAX_TOOL_NOTE)?.takeIf(String::isNotBlank), result.text)
                    .joinToString("\n\n")
                toolResult(!result.isError, text)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        searchLog.w(IllegalStateException("Hosted search failed (${e::class.simpleName.orEmpty()})")) {
            "Codex hosted search unavailable"
        }
        toolFailureResult("Unavailable")
    }
}

private const val MAX_TOOL_NOTE = 2_000

/** A context-only hook failure cannot erase the provider result, but cancellation still revokes delivery. */
private suspend fun searchResultNote(
    tools: ProfileAgentTools,
    context: AgentToolContext,
    name: String,
    arguments: JsonObject,
    result: AgentToolResult,
): String? = try {
    tools.afterHosted(context, name, arguments, result)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    searchLog.w(IllegalStateException("Search result hook failed (${e::class.simpleName.orEmpty()})")) {
        "Codex search result context unavailable"
    }
    null
}
