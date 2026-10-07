package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

/** Session capabilities isolate MCP calls; HTTP threads never wait for permissions or build jobs. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, priority = 1)
internal class DesktopAgentToolBridge(
    private val tools: ProfileAgentTools,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : AgentToolBridge {
    private val log = Log.tag("AgentToolBridge")
    private val capabilities = ConcurrentHashMap<String, Capability>()
    private val random = SecureRandom()
    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "heartbeat-agent-tools").apply { isDaemon = true }
    }
    private var server: HttpServer? = null
    override val isAvailable: Boolean = true

    init {
        profile.onClose {
            capabilities.values.forEach { it.scope.cancel() }
            capabilities.clear()
            synchronized(this@DesktopAgentToolBridge) {
                server?.stop(0)
                executor.shutdownNow()
            }
        }
    }

    override suspend fun attach(
        workspace: WorkspaceRef?,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment = attach(AgentToolScope(workspace), context)

    override suspend fun attach(
        scope: AgentToolScope,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment {
        check(!profile.isClosed) { "Agent tools profile is closed" }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val lifetime = CoroutineScope(
            profile.coroutineScope.coroutineContext + SupervisorJob(
                profile.coroutineScope.coroutineContext[kotlinx.coroutines.Job],
            ),
        )
        val capability = Capability(scope, context, lifetime)
        capabilities[token] = capability
        var isStarted = false
        val running = try {
            start().also { isStarted = true }
        } finally {
            if (!isStarted) {
                capabilities.remove(token)
                lifetime.cancel()
            }
        }
        log.i { "Attached agent tool capability" }
        return object : AgentToolBridgeAttachment {
            override val endpoint = AgentToolBridgeEndpoint("http://127.0.0.1:${running.address.port}", token)
            override fun close() {
                if (capabilities.remove(token, capability)) {
                    lifetime.cancel()
                    log.i { "Revoked agent tool capability" }
                }
            }
        }
    }

    @Synchronized
    private fun start(): HttpServer {
        check(!profile.isClosed) { "Agent tools profile is closed" }
        server?.let { return it }
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { created ->
            created.createContext("/execute") { handle(it, mcp = false) }
            created.createContext("/mcp") { handle(it, mcp = true) }
            created.executor = executor
            created.start()
            server = created
            log.i { "Agent tool bridge started" }
        }
    }

    private fun handle(exchange: HttpExchange, mcp: Boolean) {
        val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        val capability = token?.let(capabilities::get)
        if (exchange.requestMethod != "POST" || capability == null) {
            reply(exchange, HTTP_FORBIDDEN, "{}")
            return
        }
        val request = readRequest(exchange) ?: return reply(exchange, HTTP_BAD_REQUEST, "{}")
        if (mcp && request["id"] == null) {
            reply(exchange, HTTP_ACCEPTED, "")
            return
        }
        val replied = AtomicBoolean(false)
        fun respond(status: Int, body: String) {
            if (replied.compareAndSet(false, true)) reply(exchange, status, body)
        }
        // Capture authority on ingress. A queued call must never acquire a later turn's trust level.
        val invocation = capability.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            invoke(capability, request, mcp, ::respond)
        }
        invocation.invokeOnCompletion { respond(HTTP_GONE, "{}") }
    }

    private suspend fun invoke(
        capability: Capability,
        request: JsonObject,
        isMcp: Boolean,
        respond: (Int, String) -> Unit,
    ) {
        var revocation: kotlinx.coroutines.DisposableHandle? = null
        try {
            val isToolCall = !isMcp || (request["method"] as? JsonPrimitive)?.content == "tools/call"
            val context = if (isToolCall) capability.context() else null
            val job = checkNotNull(currentCoroutineContext()[kotlinx.coroutines.Job])
            revocation = context?.lifetime?.invokeOnCompletion { job.cancel() }
            // Leave the HTTP executor free; only the captured context crosses this dispatch boundary.
            yield()
            val response = if (isMcp) mcp(capability, request, context) else execute(capability, request, context)
            respond(HTTP_OK, response.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(IllegalStateException("Hosted bridge failed (${e::class.simpleName.orEmpty()})")) {
                "Tool request failed"
            }
            respond(HTTP_BAD_REQUEST, "{}")
        } finally {
            revocation?.dispose()
        }
    }

    private fun readRequest(exchange: HttpExchange): JsonObject? = try {
        val bytes = exchange.requestBody.use { it.readNBytes(MAX_REQUEST_BYTES + 1) }
        require(bytes.size <= MAX_REQUEST_BYTES) { "Tool request too large" }
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Expected an object")
    } catch (e: IllegalArgumentException) {
        log.w(IllegalArgumentException("Invalid hosted tool request (${e::class.simpleName.orEmpty()})")) {
            "Rejected invalid tool request"
        }
        null
    } catch (e: IOException) {
        log.w(IllegalArgumentException("Invalid hosted tool request (${e::class.simpleName.orEmpty()})")) {
            "Rejected invalid tool request"
        }
        null
    }

    private suspend fun execute(capability: Capability, request: JsonObject, context: AgentToolContext?): JsonObject {
        val name = (request["name"] as? JsonPrimitive)?.content.orEmpty()
        val result = if (context == null || !capability.accepts(context, name)) {
            AgentToolResult("No active turn for this capability", isError = true)
        } else {
            val arguments = request["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            val call = (request["callId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: Uuid.random().toString()
            tools.execute(context.copy(callId = ToolCallId(call)), name, arguments)
        }
        return buildJsonObject {
            put("success", !result.isError)
            put("text", result.text.take(MAX_RESPONSE_CHARS))
            put(
                "images",
                JsonArray(
                    result.images.map { image ->
                        buildJsonObject {
                            put("type", "image")
                            put("mimeType", image.mimeType)
                            put("data", image.data)
                        }
                    },
                ),
            )
        }
    }

    private fun Capability.accepts(context: AgentToolContext, name: String): Boolean {
        if (context.workspace != toolScope.workspace) return false
        if (toolScope.session != null && context.session != toolScope.session) return false
        return toolScope.declared?.contains(name) != false
    }

    private suspend fun mcp(capability: Capability, request: JsonObject, context: AgentToolContext?): JsonObject {
        val method = (request["method"] as? JsonPrimitive)?.content.orEmpty()
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        val result: JsonElement = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", (params["protocolVersion"] as? JsonPrimitive)?.content ?: "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                put(
                    "serverInfo",
                    buildJsonObject {
                        put("name", HOSTED_TOOLS_SERVER)
                        put("version", "1")
                    },
                )
                put("instructions", tools.instructions(capability.toolScope))
            }

            "ping" -> buildJsonObject {}

            "tools/list" -> buildJsonObject {
                put(
                    "tools",
                    JsonArray(
                        tools.specifications(capability.toolScope).map { spec ->
                            buildJsonObject {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("inputSchema", spec.inputSchema)
                            }
                        },
                    ),
                )
            }

            "tools/call" -> {
                val call = JsonObject(params + ("callId" to JsonPrimitive("mcp-${request["id"]}")))
                val output = execute(capability, call, context)
                buildJsonObject {
                    put("isError", output["success"] != JsonPrimitive(true))
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", output["text"] ?: JsonPrimitive(""))
                                },
                            ) + (output["images"] as? JsonArray).orEmpty(),
                        ),
                    )
                }
            }

            else -> return mcpResponse(
                request,
                "error",
                buildJsonObject {
                    put("code", -32601)
                    put("message", "Unknown method")
                },
            )
        }
        return mcpResponse(request, "result", result)
    }

    private fun mcpResponse(request: JsonObject, field: String, payload: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request["id"] ?: JsonNull)
        put(field, payload)
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else bytes.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        } catch (e: IOException) {
            log.w(e) { "Tool client disconnected" }
        } finally {
            exchange.close()
        }
    }

    private data class Capability(
        val toolScope: AgentToolScope,
        val context: suspend () -> AgentToolContext?,
        val scope: CoroutineScope,
    )

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_ACCEPTED = 202
        const val HTTP_FORBIDDEN = 403
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_GONE = 410
        const val MAX_REQUEST_BYTES = 512 * 1024
        const val MAX_RESPONSE_CHARS = 128 * 1024
    }
}
