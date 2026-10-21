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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridge
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeAccess
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeAttachment
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeEndpoint
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeGuard
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val attachments = CopyOnWriteArrayList<EngineFeatures>()
    private val lock = Any()
    private val accesses = mutableMapOf<String, ScopedSearch>()
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var isClosed = false

    init {
        profile.onClose(::stop)
    }

    /** Starts the loopback server on first use; a closed profile never restarts it. */
    override fun endpoint(): SearchBridgeEndpoint = synchronized(lock) {
        check(!isClosed) { "Search bridge is closed" }
        val running = server ?: start()
        SearchBridgeEndpoint("http://127.0.0.1:${running.address.port}", token)
    }

    override fun attach(guard: SearchBridgeGuard): SearchBridgeAccess = synchronized(lock) {
        check(!isClosed) { "Search bridge is closed" }
        check(accesses.size < MAX_ACCESSES) { "Search bridge capacity reached" }
        val running = server ?: start()
        val key = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))
        val access = ScopedSearch(guard, SupervisorJob(scope.coroutineContext[Job]))
        accesses[key] = access
        object : SearchBridgeAccess {
            override val endpoint = SearchBridgeEndpoint("http://127.0.0.1:${running.address.port}", key)
            override fun close() {
                synchronized(lock) { if (accesses[key] === access) accesses.remove(key) }
                access.lifetime.cancel()
            }
        }
    }

    /** Live engines publish their native web operations here; the routed tools prefer them over the provider. */
    override fun attach(features: EngineFeatures): SearchBridgeAttachment {
        attachments.add(features)
        log.i { "Native search features attached" }
        return SearchBridgeAttachment {
            if (attachments.remove(features)) log.i { "Native search features detached" }
        }
    }

    /** Composite of the live attachments, or null while no engine has published native operations. */
    private fun attachedFeatures(): EngineFeatures? {
        val snapshot = attachments.toList()
        return if (snapshot.isEmpty()) null else AttachedFeatures(snapshot)
    }

    private fun start(): HttpServer {
        val threads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(MAX_THREADS) { task ->
            Thread(task, "heartbeat-search-bridge-${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        var isStarted = false
        var created: HttpServer? = null
        try {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            created = server
            server.createContext("/mcp", ::handleMcp)
            server.createContext("/execute", ::handleExecute)
            server.setExecutor(pool)
            server.start()
            this.server = server
            executor = pool
            isStarted = true
            log.i { "Search bridge started on port ${server.address.port}" }
            return server
        } catch (e: IOException) {
            log.e(e) { "Search bridge failed to start" }
            throw e
        } finally {
            // Any failure, not only IO, releases the threads.
            if (!isStarted) {
                created?.stop(0)
                pool.shutdownNow()
            }
        }
    }

    private fun stop() {
        val (running, pool) = synchronized(lock) {
            isClosed = true
            accesses.values.forEach { it.lifetime.cancel() }
            accesses.clear()
            (server to executor).also {
                server = null
                executor = null
            }
        }
        running?.stop(0)
        pool?.shutdownNow()
        if (running != null) log.i { "Search bridge stopped" }
    }

    private fun handleMcp(exchange: HttpExchange) = handle(exchange, mcp = true)
    private fun handleExecute(exchange: HttpExchange) = handle(exchange, mcp = false)

    private fun handle(exchange: HttpExchange, mcp: Boolean) {
        try {
            val access = authorized(exchange)
            if (exchange.requestMethod != "POST" || access == null) {
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
            val response = dispatch(request, mcp, access)
            reply(exchange, OK, response.toString())
        } catch (e: TimeoutException) {
            log.w(e) { "Bridge request timed out" }
            reply(exchange, UNAVAILABLE, "{}")
        } catch (e: BridgeUnavailableException) {
            log.w(e) { "Bridge request cancelled" }
            reply(exchange, UNAVAILABLE, "{}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(IllegalStateException("Bridge request failed (${e::class.simpleName.orEmpty()})")) {
                "Bridge request failed"
            }
            reply(exchange, BAD_REQUEST, "{}")
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(request: JsonObject, mcp: Boolean, access: SearchAccess): JsonObject {
        val pending = CompletableFuture<JsonObject>()
        val job = scope.launch((access as? ScopedSearch)?.lifetime ?: scope.coroutineContext) {
            try {
                currentCoroutineContext().ensureActive()
                pending.complete(if (mcp) mcp(request, access) else execute(request, access))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending.completeExceptionally(e)
            }
        }
        // A cancelled profile scope becomes a typed failure, so the HTTP thread answers 503 instead of propagating.
        job.invokeOnCompletion { error ->
            if (error != null) pending.completeExceptionally(BridgeUnavailableException(error))
        }
        return await(pending, job, isScoped = access is ScopedSearch)
    }

    private fun await(pending: CompletableFuture<JsonObject>, job: Job, isScoped: Boolean): JsonObject = try {
        // Scoped authorization can wait for a person; only the search IO consumes its timeout budget.
        if (isScoped) pending.get() else pending.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        job.cancel()
        throw e
    } catch (e: ExecutionException) {
        // Unwraps the tool failure so logs show the real cause.
        // A cancelled scope already completes the future with BridgeUnavailableException (503).
        throw e.cause ?: e
    } finally {
        // An interrupted HTTP worker must not leave a detached authorization or search coroutine.
        if (!pending.isDone) job.cancel()
    }

    private fun authorized(exchange: HttpExchange): SearchAccess? {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        val actual = header.removePrefix("Bearer ")
        return synchronized(lock) {
            if (isClosed) {
                null
            } else if (MessageDigest.isEqual(token.toByteArray(), actual.toByteArray())) {
                LegacySearch
            } else {
                accesses[actual]?.takeIf { it.lifetime.isActive }
            }
        }
    }

    private suspend fun mcp(request: JsonObject, access: SearchAccess): JsonObject {
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
                val result = tool(name, args, access)
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

    private suspend fun execute(request: JsonObject, access: SearchAccess): JsonObject {
        val name = (request["name"] as? JsonPrimitive)?.content.orEmpty()
        val args = request["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val result = tool(name, args, access)
        return buildJsonObject {
            put("success", result.second)
            put("text", result.first)
        }
    }

    private suspend fun tool(name: String, args: JsonObject, access: SearchAccess): Pair<String, Boolean> {
        val result = if (access is ScopedSearch) {
            val answer = executeScopedSearch(access.guard, name, args, access.lifetime) {
                runTool(name, args, native = null).let { AgentToolResult(it.first, isError = !it.second) }
            }
            answer.text to !answer.isError
        } else {
            runTool(name, args, attachedFeatures())
        }
        log.i { "Bridge tool completed success=${result.second}" }
        return result
    }

    private suspend fun runTool(name: String, args: JsonObject, native: EngineFeatures?): Pair<String, Boolean> {
        if (name != "web_search" && name != "web_fetch") return "InvalidInput" to false
        return executeTool(name, args, native)
    }

    private suspend fun executeTool(name: String, args: JsonObject, native: EngineFeatures?): Pair<String, Boolean> =
        try {
            val text = when (name) {
                "web_search" -> {
                    val query = (args["query"] as? JsonPrimitive)?.content.orEmpty()
                    val count = ((args["count"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)
                    JsonArray(
                        search.search(query, count, native).map { result ->
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
                    val result = search.fetch(url, native)
                    buildJsonObject {
                        put(
                            "url",
                            result.url,
                        )
                        put("title", result.title)
                        put("content", result.text)
                    }.toString()
                }

                else -> error("Unsupported search operation")
            }
            text to true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchException) {
            log.w(e) { "Search tool failed" }
            toolFailure(name, e) to false
        } catch (e: Exception) {
            log.w(e) { "Search tool failed" }
            "Unavailable" to false
        }

    /** Model-facing failure text: the structured one-line verdict, or the typed failure name. */
    private fun toolFailure(tool: String, error: SearchException): String =
        error.details?.let { "$tool failed: $it" } ?: error.failure.name

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
        log.d { "Bridge ${exchange.requestURI.path}: $status" }
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
        const val UNAVAILABLE = 503
        const val MAX_THREADS = 4
        const val MAX_ACCESSES = 64
        const val REQUEST_TIMEOUT_SECONDS = 60L
        const val DEFAULT_COUNT = 5
        const val MAX_COUNT = 20
    }
}

private sealed interface SearchAccess
private data object LegacySearch : SearchAccess
private data class ScopedSearch(val guard: SearchBridgeGuard, val lifetime: Job) : SearchAccess

/** Routes each feature lookup to the first attachment providing it; blocked access survives only if none does. */
private class AttachedFeatures(private val owners: List<EngineFeatures>) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        var blocked: FeatureAccess.Unavailable? = null
        for (owner in owners) {
            when (val access = owner.resolve(key)) {
                is FeatureAccess.Available -> return access
                is FeatureAccess.Unavailable -> if (blocked == null) blocked = access
                FeatureAccess.Unsupported -> Unit
            }
        }
        return blocked ?: FeatureAccess.Unsupported
    }
}

/** The profile scope stopped the request before it completed. */
private class BridgeUnavailableException(cause: Throwable) : Exception("Search bridge unavailable", cause)

private fun invalidRequest(): Nothing = throw IllegalArgumentException("Invalid bridge request")

/** Authorization has no IO deadline; a consumed operation has its own bounded search budget. */
internal suspend fun executeScopedSearch(
    guard: SearchBridgeGuard,
    name: String,
    arguments: JsonObject,
    lifetime: Job,
    timeoutMillis: Long = 60_000L,
    operation: suspend () -> AgentToolResult,
): AgentToolResult {
    val invoked = AtomicBoolean()
    val invocation = currentCoroutineContext()
    return guard.execute(name, arguments) {
        invocation.ensureActive()
        lifetime.ensureActive()
        check(invoked.compareAndSet(false, true)) { "Search operation already consumed" }
        withTimeoutOrNull(timeoutMillis) { operation() } ?: AgentToolResult("Timeout", isError = true)
    }
}
