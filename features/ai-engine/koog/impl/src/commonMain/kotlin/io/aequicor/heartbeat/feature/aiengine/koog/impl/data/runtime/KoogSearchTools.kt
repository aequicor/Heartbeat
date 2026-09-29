package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val koogSearchTools = listOf(
    ToolDescriptor(
        "web_search",
        "Find web sources and citation URLs for a query.",
        listOf(ToolParameterDescriptor("query", "Search query", ToolParameterType.String)),
        listOf(ToolParameterDescriptor("count", "Maximum result count, 1 to 20", ToolParameterType.Integer)),
    ),
    ToolDescriptor(
        "web_fetch",
        "Read the text content of one web URL.",
        listOf(ToolParameterDescriptor("url", "HTTP or HTTPS URL", ToolParameterType.String)),
        emptyList(),
    ),
)

/** Read-only search tools backed by [search]. */
internal fun koogSearchToolset(search: SearchEngine): List<KoogTool> = koogSearchTools.map { tool ->
    object : KoogTool {
        override val descriptor = tool

        override suspend fun run(args: JsonObject) = executeKoogSearch(search, tool.name, args)
    }
}

private val searchLog = Log.tag("KoogSearchTool")

internal suspend fun executeKoogSearch(search: SearchEngine, call: StreamFrame.ToolCallComplete): KoogToolResult =
    executeKoogSearch(search, call.name, call.contentJson)

private suspend fun executeKoogSearch(search: SearchEngine, name: String, args: JsonObject): KoogToolResult = try {
    when (name) {
        "web_search" -> {
            val results = search.search(
                args.argText("query"),
                (args.argInt("count") ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT),
            )
            val output = JsonArray(
                results.map { result ->
                    buildJsonObject {
                        put("url", result.url)
                        put("title", result.title)
                        put("snippet", result.snippet)
                    }
                },
            ).toString()
            KoogToolResult(output, false, results.map { ResourceRef(it.url, "text/html") })
        }

        "web_fetch" -> search.fetch(args.argText("url")).let { result ->
            val output = buildJsonObject {
                put("url", result.url)
                put("title", result.title)
                put("content", result.text)
            }.toString()
            KoogToolResult(output, false, listOf(ResourceRef(result.url, "text/html")))
        }

        else -> KoogToolResult("InvalidInput", true)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: SearchException) {
    searchLog.w(e) { "Search tool failed" }
    KoogToolResult(e.failure.name, true)
} catch (e: Exception) {
    searchLog.w(e) { "Search tool failed" }
    KoogToolResult("Unavailable", true)
}

private const val DEFAULT_COUNT = 5
private const val MAX_COUNT = 20
