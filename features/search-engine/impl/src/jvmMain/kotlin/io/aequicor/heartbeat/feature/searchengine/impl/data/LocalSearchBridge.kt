package io.aequicor.heartbeat.feature.searchengine.impl.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridge
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeEndpoint
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture

/** Loopback-only, profile-lifetime MCP/JSON bridge; child engines never receive Querit keys. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class LocalSearchBridge(
    private val search: SearchEngine,
    @ForScope(ProfileScope::class) profile: ScopeHandle,
) : SearchBridge {
    private val log = Log.tag("SearchBridge")
    private val scope = profile.coroutineScope
    private val bearer = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bearer)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/mcp", ::handleMcp)
        createContext("/execute", ::handleExecute)
        start()
    }
    init {
        profile.onClose { server.stop(0) }
    }

    override fun endpoint(): SearchBridgeEndpoint = SearchBridgeEndpoint(
        "http://127.0.0.1:${server.address.port}",
        token,
    )

    private fun handleMcp(exchange: HttpExchange) = handle(exchange, mcp = true)
    private fun handleExecute(exchange: HttpExchange) = handle(exchange, mcp = false)

    private fun handle(exchange: HttpExchange, mcp: Boolean) {
        try {
            if (exchange.requestMethod != "POST" || !authorized(exchange)) {
                reply(exchange, FORBIDDEN, "{}")
                return
            }
            val request = exchange.requestBody.use { stream ->
                val bytes = stream.readNBytes(MAX_REQUEST_BYTES + 1)
                if (bytes.size > MAX_REQUEST_BYTES) invalidRequest()
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                    ?: invalidRequest()
            }
            if (mcp && request["id"] == null) {
                reply(exchange, ACCEPTED, "")
                return
            }
            val response = dispatch(request, mcp)
            reply(exchange, OK, response.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Bridge request failed" }
            reply(exchange, BAD_REQUEST, "{}")
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(request: JsonObject, mcp: Boolean): JsonObject {
        val pending = CompletableFuture<JsonObject>()
        val job = scope.launch {
            try {
                pending.complete(if (mcp) mcp(request) else execute(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending.completeExceptionally(e)
            }
        }
        job.invokeOnCompletion { error ->
            if (error != null) pending.completeExceptionally(error)
        }
        return pending.get()
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val actual = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ") ?: return false
        return MessageDigest.isEqual(token.toByteArray(), actual.toByteArray())
    }

    private suspend fun mcp(request: JsonObject): JsonObject {
        val id = request["id"] ?: JsonNull
        val method = (request["method"] as? JsonPrimitive)?.content.orEmpty()
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        val result: JsonElement = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                put(
                    "serverInfo",
                    buildJsonObject {
                        put("name", "heartbeat-search")
                        put("version", "1.0")
                    },
                )
            }

            "tools/list" -> buildJsonObject { put("tools", mcpTools()) }

            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.content.orEmpty()
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val result = tool(name, args)
                buildJsonObject {
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", result.first)
                                },
                            ),
                        ),
                    )
                    put("isError", !result.second)
                }
            }

            else -> return buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32601)
                        put("message", "Method not found")
                    },
                )
            }
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
    }

    private suspend fun execute(request: JsonObject): JsonObject {
        val name = (request["name"] as? JsonPrimitive)?.content.orEmpty()
        val args = request["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val result = tool(name, args)
        return buildJsonObject {
            put("success", result.second)
            put("text", result.first)
        }
    }

    private suspend fun tool(name: String, args: JsonObject): Pair<String, Boolean> = try {
        val text = when (name) {
            "web_search" -> {
                val query = (args["query"] as? JsonPrimitive)?.content.orEmpty()
                val count = (args["count"] as? JsonPrimitive)?.intOrNull ?: 5
                JsonArray(
                    search.search(query, count).map { result ->
                        buildJsonObject {
                            put("url", result.url)
                            put("title", result.title)
                            put("snippet", result.snippet)
                        }
                    },
                ).toString()
            }

            "web_fetch" -> {
                val url = (args["url"] as? JsonPrimitive)?.content.orEmpty()
                val result = search.fetch(url)
                buildJsonObject {
                    put(
                        "url",
                        result.url,
                    )
                    put("title", result.title)
                    put("content", result.text)
                }.toString()
            }

            else -> return "InvalidInput" to false
        }
        text to true
    } catch (e: CancellationException) {
        throw e
    } catch (e: SearchException) {
        log.w(e) { "Search tool failed" }
        e.failure.name to false
    } catch (e: Exception) {
        log.w(e) { "Search tool failed" }
        "Unavailable" to false
    }

    private fun mcpTools(): JsonArray = JsonArray(
        listOf(
            mcpTool("web_search", "Find web sources and citation URLs", "query", "string"),
            mcpTool("web_fetch", "Read text content of a URL", "url", "string"),
        ),
    )

    private fun mcpTool(name: String, description: String, field: String, type: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put(
            "inputSchema",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put(field, buildJsonObject { put("type", type) }) })
                put("required", JsonArray(listOf(JsonPrimitive(field))))
            },
        )
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val OK = 200
        const val ACCEPTED = 202
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
    }
}

private fun invalidRequest(): Nothing = throw IllegalArgumentException("Invalid bridge request")
