package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
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

internal class DefaultAcpConnection(
    transport: AcpTransport,
    private val scope: CoroutineScope,
    handler: AcpClientHandler,
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

    override suspend fun initialize(client: AcpImplementation): AcpInitialization = scope.async {
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
            val result = acpJson.decodeFromJsonElement(AcpInitialization.serializer(), response)
            if (result.protocolVersion != PROTOCOL_VERSION) throw AcpException.Protocol()
            lock.withLock { initialization = result }
            isReady = true
            result
        } finally {
            if (!isReady) withContext(NonCancellable) { peer.close() }
        }
    }.await()

    override suspend fun authenticate(methodId: String) {
        scope.async {
            val info = ready()
            require(info.authMethods.any { it.id == methodId }) { "Authentication method was not advertised" }
            log.i { "ACP authenticate" }
            peer.request("authenticate", fields("methodId" to JsonPrimitive(methodId)))
        }.await()
    }

    override suspend fun newSession(cwd: String): AcpSession = scope.async {
        ready()
        requireAbsolute(cwd)
        log.i { "ACP new session" }
        val result = peer.request("session/new", sessionParams(cwd))
        val id = result.string("sessionId")
        require(id.isNotBlank()) { "Empty native session id" }
        lock.withLock { sessions.add(id) }
        AcpSession(id, result)
    }.await()

    override suspend fun loadSession(sessionId: String, cwd: String): AcpSession = scope.async {
        val info = ready()
        check((info.agentCapabilities["loadSession"] as? JsonPrimitive)?.booleanOrNull == true) {
            "Agent does not support loading sessions"
        }
        require(sessionId.isNotBlank()) { "Session id is required" }
        requireAbsolute(cwd)
        log.i { "ACP load session" }
        lock.withLock { check(busySessions.add(sessionId)) { "Session is busy" } }
        try {
            val result = peer.request("session/load", JsonObject(sessionParams(cwd) + sessionId(sessionId)))
            lock.withLock { sessions.add(sessionId) }
            AcpSession(sessionId, result)
        } finally {
            withContext(NonCancellable) { lock.withLock { busySessions.remove(sessionId) } }
        }
    }.await()

    override suspend fun prompt(sessionId: String, text: String): AcpPromptResult = scope.async {
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
                peer.submit("session/prompt", promptParams(sessionId, text))
            }
            acpJson.decodeFromJsonElement(AcpPromptResult.serializer(), response.await())
        } finally {
            if (isTurnOwned) withContext(NonCancellable) { lock.withLock { busySessions.remove(sessionId) } }
        }
    }.await()

    override suspend fun cancel(sessionId: String) {
        scope.async {
            admissions.withLock {
                requireSession(sessionId)
                log.i { "ACP cancel" }
                permissions.cancel(sessionId)
                peer.notify("session/cancel", fields(sessionId(sessionId)))
            }
        }.await()
    }

    private fun promptParams(sessionId: String, text: String): JsonObject = fields(
        sessionId(sessionId),
        "prompt" to JsonArray(listOf(fields("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))),
    )

    override suspend fun close() {
        log.i { "ACP close requested" }
        withContext(NonCancellable) { peer.close() }
    }

    private fun peer(): AcpRpcPeer = peer

    private suspend fun ready(): AcpInitialization =
        lock.withLock { checkNotNull(initialization) { "Initialize ACP before using the connection" } }

    private suspend fun requireSession(id: String) {
        ready()
        lock.withLock { require(id in sessions) { "Session does not belong to this connection" } }
    }

    private fun sessionParams(cwd: String): JsonObject =
        fields("cwd" to JsonPrimitive(cwd), "mcpServers" to JsonArray(emptyList()))

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
