package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import kotlinx.coroutines.CancellationException
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

internal suspend fun executeSearchTool(search: SearchEngine, tool: String, arguments: JsonElement): JsonObject {
    val args = arguments as? JsonObject ?: return toolFailure("InvalidInput")
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

            else -> return toolFailure("InvalidInput")
        }
        toolResult(true, output.toString())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SearchException) {
        searchLog.w(e) { "Search tool failed" }
        toolFailure(e.failure.name)
    } catch (e: Exception) {
        searchLog.w(e) { "Search tool failed" }
        toolFailure("Unavailable")
    }
}

private fun toolFailure(reason: String): JsonObject = toolResult(false, reason)

/** Failed dynamic tool result that carries only a safe reason code. */
internal fun toolFailureResult(reason: String): JsonObject = toolFailure(reason)

private const val DEFAULT_COUNT = 5
private const val MAX_COUNT = 20

internal fun toolResult(success: Boolean, text: String): JsonObject = buildJsonObject {
    put("success", success)
    put(
        "contentItems",
        JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "inputText")
                    put("text", text)
                },
            ),
        ),
    )
}
