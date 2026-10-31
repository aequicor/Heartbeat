package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionList
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal class DefaultAcpConnection(
    transport: AcpTransport,
    private val scope: CoroutineScope,
    private val handler: AcpClientHandler,
) : AcpConnection {
    private val log = Log.tag("DefaultAcpConnection")
    private val lock = Mutex()
    private val admissions = Mutex()
    private var hasInitialized = false
    private var initialization: AcpInitialization? = null
    private val sessions = mutableSetOf<String>()
    private val busySessions = mutableSetOf<String>()
    private val permissions = AcpPermissions(handler, scope)
    private val peer = AcpRpcPeer(
        transport,
        scope,
        onClosed = { handler.onDisconnected() },
        onNotification = { method, params ->
            if (method == "session/update") {
                handler.onSessionUpdate(AcpSessionUpdate(params.string("sessionId"), params.obj("update")))
            }
        },
        onRequest = { id, method, params ->
            if (method == "session/request_permission") {
                permissions.receive(id, params, peer())
            } else {
                peer().reject(id, METHOD_NOT_FOUND)
            }
        },
    )

    init {
        peer.start()
    }

    override suspend fun initialize(client: AcpImplementation): AcpInitialization = owned {
        lock.withLock {
            check(!hasInitialized) { "ACP initialization already attempted" }
            hasInitialized = true
        }
        var isReady = false
        try {
            log.i { "ACP initialize" }
            val response = peer.request(
                "initialize",
                fields(
                    "protocolVersion" to JsonPrimitive(PROTOCOL_VERSION),
                    "clientInfo" to acpJson.encodeToJsonElement(AcpImplementation.serializer(), client),
                    "clientCapabilities" to JsonObject(emptyMap()),
                ),
            )
            val result = parse { acpJson.decodeFromJsonElement(AcpInitialization.serializer(), response) }
            if (result.protocolVersion != PROTOCOL_VERSION) throw AcpException.Protocol()
            lock.withLock { initialization = result }
            isReady = true
            result
        } finally {
            if (!isReady) withContext(NonCancellable) { peer.close() }
        }
    }

    override suspend fun authenticate(methodId: String) {
        owned {
            val info = ready()
            require(info.authMethods.any { it.id == methodId }) { "Authentication method was not advertised" }
            log.i { "ACP authenticate" }
            peer.request("authenticate", fields("methodId" to JsonPrimitive(methodId)))
        }
    }

    override suspend fun authenticate(methodId: String, metadata: JsonObject): JsonObject = owned {
        val info = ready()
        require(info.authMethods.any { it.id == methodId }) { "Authentication method was not advertised" }
        log.i { "ACP authenticate with metadata" }
        peer.request("authenticate", fields("methodId" to JsonPrimitive(methodId), "_meta" to metadata))
    }

    override suspend fun logout() {
        owned {
            val auth = ready().agentCapabilities["auth"] as? JsonObject
            check(auth?.get("logout") is JsonObject) { "ACP logout was not advertised" }
            log.i { "ACP logout" }
            peer.request("logout", JsonObject(emptyMap()))
        }
    }

    override suspend fun newSession(cwd: String): AcpSession = newSession(cwd, emptyList())

    override suspend fun newSession(cwd: String, servers: List<JsonObject>): AcpSession = owned {
        ready()
        requireAbsolute(cwd)
        log.i { "ACP new session" }
        val result = peer.request("session/new", sessionParams(cwd, servers))
        val id = parse { result.string("sessionId").also { if (it.isBlank()) throw AcpException.Protocol() } }
        lock.withLock { sessions.add(id) }
        AcpSession(id, result)
    }

    override suspend fun loadSession(sessionId: String, cwd: String): AcpSession = loadSession(
        sessionId,
        cwd,
        emptyList(),
    )

    override suspend fun loadSession(sessionId: String, cwd: String, servers: List<JsonObject>): AcpSession = owned {
        val info = ready()
        check((info.agentCapabilities["loadSession"] as? JsonPrimitive)?.booleanOrNull == true) {
            "Agent does not support loading sessions"
        }
        require(sessionId.isNotBlank()) { "Session id is required" }
        requireAbsolute(cwd)
        log.i { "ACP load session" }
        lock.withLock { check(busySessions.add(sessionId)) { "Session is busy" } }
        try {
            val params = JsonObject(sessionParams(cwd, servers) + sessionId(sessionId))
            val result = peer.request("session/load", params)
            lock.withLock { sessions.add(sessionId) }
            AcpSession(sessionId, result)
        } finally {
            withContext(NonCancellable) { lock.withLock { busySessions.remove(sessionId) } }
        }
    }

    override suspend fun resumeSession(sessionId: String, cwd: String): AcpSession = owned {
        ready()
        require(sessionId.isNotBlank()) { "Session id is required" }
        requireAbsolute(cwd)
        log.i { "ACP resume session" }
        lock.withLock { check(busySessions.add(sessionId)) { "Session is busy" } }
        try {
            val params = JsonObject(sessionParams(cwd, emptyList()) + sessionId(sessionId))
            val result = peer.request("session/resume", params)
            lock.withLock { sessions.add(sessionId) }
            AcpSession(sessionId, result)
        } finally {
            withContext(NonCancellable) { lock.withLock { busySessions.remove(sessionId) } }
        }
    }

    override suspend fun listSessions(cwd: String?, cursor: String?): AcpSessionList = owned {
        ready()
        cwd?.let { requireAbsolute(it) }
        log.i { "ACP list sessions" }
        val params = buildJsonObject {
            cwd?.let { put("cwd", JsonPrimitive(it)) }
            cursor?.let { put("cursor", JsonPrimitive(it)) }
        }
        val result = peer.request("session/list", params)
        parse {
            val entries = result["sessions"] as? JsonArray ?: throw AcpException.Protocol()
            val listed = entries.map { entry ->
                val obj = entry as? JsonObject ?: throw AcpException.Protocol()
                val id = (obj["sessionId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: throw AcpException.Protocol()
                AcpSession(id, obj)
            }
            val next = result["nextCursor"]?.let { cursorElement ->
                (cursorElement as? JsonPrimitive)?.contentOrNull ?: throw AcpException.Protocol()
            }
            AcpSessionList(listed, next)
        }
    }

    override suspend fun closeSession(sessionId: String) {
        owned {
            admissions.withLock {
                requireSession(sessionId)
                lock.withLock { check(sessionId !in busySessions) { "Session is busy" } }
                log.i { "ACP close session" }
                peer.request("session/close", fields(sessionId(sessionId)))
                lock.withLock { sessions.remove(sessionId) }
            }
        }
    }

    override suspend fun prompt(sessionId: String, content: List<JsonObject>): AcpPromptResult =
        prompt(sessionId, content, JsonObject(emptyMap()))

    override suspend fun prompt(sessionId: String, content: List<JsonObject>, metadata: JsonObject): AcpPromptResult =
        owned {
            var isTurnOwned = false
            try {
                val response = admissions.withLock {
                    requireSession(sessionId)
                    lock.withLock {
                        check(busySessions.add(sessionId)) { "Session is busy" }
                        isTurnOwned = true
                    }
                    permissions.begin(sessionId)
                    log.i { "ACP prompt" }
                    peer.submit("session/prompt", promptParams(sessionId, content, metadata)).also {
                        try {
                            handler.onPromptSubmitted(sessionId)
                        } catch (error: CancellationException) {
                            withContext(NonCancellable) { peer.close() }
                            throw error
                        } catch (error: Exception) {
                            log.w(AcpDiagnostic(error)) { "ACP prompt submission observer failed" }
                            withContext(NonCancellable) { peer.close() }
                            throw error
                        }
                    }
                }
                val body = response.await()
                parse { acpJson.decodeFromJsonElement(AcpPromptResult.serializer(), body) }
            } finally {
                if (isTurnOwned) withContext(NonCancellable) { lock.withLock { busySessions.remove(sessionId) } }
            }
        }

    override suspend fun cancel(sessionId: String) {
        owned {
            admissions.withLock {
                requireSession(sessionId)
                log.i { "ACP cancel" }
                permissions.cancel(sessionId)
                peer.notify("session/cancel", fields(sessionId(sessionId)))
            }
        }
    }

    override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) {
        owned {
            val response = admissions.withLock {
                requireSession(sessionId)
                lock.withLock { check(sessionId !in busySessions) { "Session is busy" } }
                log.i { "ACP set config option" }
                peer.submit(
                    "session/set_config_option",
                    fields(
                        sessionId(sessionId),
                        "configId" to JsonPrimitive(optionId),
                        "value" to JsonPrimitive(value),
                    ),
                )
            }
            response.await()
        }
    }

    private fun promptParams(sessionId: String, content: List<JsonObject>, metadata: JsonObject): JsonObject {
        require(content.isNotEmpty()) { "ACP prompt requires at least one content block" }
        val params = fields(sessionId(sessionId), "prompt" to JsonArray(content))
        return if (metadata.isEmpty()) params else JsonObject(params + ("_meta" to metadata))
    }

    override suspend fun close() {
        log.i { "ACP close requested" }
        withContext(NonCancellable) { peer.close() }
    }

    private fun peer(): AcpRpcPeer = peer

    /**
     * Runs [block] in the connection scope, so cancelling the caller only abandons its wait.
     * Connection shutdown cancels that scope; the still active caller gets [AcpException.Disconnected].
     */
    private suspend fun <T> owned(block: suspend CoroutineScope.() -> T): T {
        val result = CompletableDeferred<T>()
        val work = scope.async { result.complete(block()) }
        work.invokeOnCompletion { cause ->
            when (cause) {
                null -> Unit

                is CancellationException -> {
                    log.w(AcpDiagnostic(cause)) { "ACP operation stopped by connection shutdown" }
                    result.completeExceptionally(AcpException.Disconnected())
                }

                else -> result.completeExceptionally(cause)
            }
        }
        return result.await()
    }

    /** An invalid agent response is a protocol violation: the connection closes and the caller gets Protocol. */
    private suspend fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        log.w(AcpDiagnostic(e)) { "ACP invalid response" }
        protocolViolation()
    } catch (e: AcpException.Protocol) {
        log.w(AcpDiagnostic(e)) { "ACP invalid response" }
        protocolViolation()
    }

    private suspend fun protocolViolation(): Nothing {
        withContext(NonCancellable) { peer.close() }
        throw AcpException.Protocol()
    }

    private suspend fun ready(): AcpInitialization =
        lock.withLock { checkNotNull(initialization) { "Initialize ACP before using the connection" } }

    private suspend fun requireSession(id: String) {
        ready()
        lock.withLock { require(id in sessions) { "Session does not belong to this connection" } }
    }

    private fun sessionParams(cwd: String, servers: List<JsonObject>): JsonObject =
        fields("cwd" to JsonPrimitive(cwd), "mcpServers" to JsonArray(servers))

    private fun sessionId(id: String): Pair<String, JsonPrimitive> = "sessionId" to JsonPrimitive(id)

    private fun requireAbsolute(cwd: String) {
        require(cwd.startsWith("/") || UNC_ABSOLUTE.matches(cwd) || WINDOWS_ABSOLUTE.matches(cwd)) {
            "ACP requires an absolute working directory"
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val METHOD_NOT_FOUND = -32601
        val UNC_ABSOLUTE = Regex("""\\\\[^\\]+\\[^\\]+(?:\\.*)?""")
        val WINDOWS_ABSOLUTE = Regex("[A-Za-z]:[\\\\/].*")
    }
}
