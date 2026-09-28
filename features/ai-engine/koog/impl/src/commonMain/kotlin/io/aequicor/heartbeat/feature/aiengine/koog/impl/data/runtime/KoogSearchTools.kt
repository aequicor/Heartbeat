package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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

internal data class KoogSearchResult(val text: String, val isFailed: Boolean)

private val searchLog = Log.tag("KoogSearchTool")

internal suspend fun executeKoogSearch(search: SearchEngine, call: StreamFrame.ToolCallComplete): KoogSearchResult =
    try {
        val args = call.contentJson
        val output = when (call.name) {
            "web_search" -> JsonArray(
                search.search(
                    args.string("query"),
                    args.integer("count") ?: 5,
                ).map { result ->
                    buildJsonObject {
                        put("url", result.url)
                        put("title", result.title)
                        put("snippet", result.snippet)
                    }
                },
            ).toString()

            "web_fetch" -> search.fetch(args.string("url")).let { result ->
                buildJsonObject {
                    put("url", result.url)
                    put("title", result.title)
                    put("content", result.text)
                }
            }.toString()

            else -> return KoogSearchResult("InvalidInput", true)
        }
        KoogSearchResult(output, false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SearchException) {
        searchLog.w(e) { "Search tool failed" }
        KoogSearchResult(e.failure.name, true)
    } catch (e: Exception) {
        searchLog.w(e) { "Search tool failed" }
        KoogSearchResult("Unavailable", true)
    }

private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.integer(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
