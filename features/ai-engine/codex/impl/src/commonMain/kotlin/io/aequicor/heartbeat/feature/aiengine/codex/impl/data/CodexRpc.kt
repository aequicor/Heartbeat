package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Confined to the injected main dispatcher; readers never block waiting for event consumers. */
internal class CodexRpc(private val wire: CodexWire, scope: CoroutineScope) : AutoCloseable {
    private val log = Log.tag("CodexRpc")
    var home: String? = null
        private set
    private var nextId = 0L
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private val events = Channel<JsonObject>(EVENT_CAPACITY)
    val notifications = events.receiveAsFlow()
    private var failure: EngineException? = null
    private val reader = scope.launch {
        try {
            wire.messages.collect { message -> receive(message) }
            shutdown(EngineException(EngineFailure.Engine(EngineFailureReason.Crashed)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex connection failed" }
            shutdown(e)
        }
    }

    /** [experimentalApi] opts into dynamic tools; without it the handshake is the stable one. */
    suspend fun initialize(experimentalApi: Boolean = false) {
        val response = request(
            "initialize",
            buildJsonObject {
                put("clientInfo", json("name" to "heartbeat".json(), "version" to "0.1.0".json()))
                if (experimentalApi) put("capabilities", json("experimentalApi" to JsonPrimitive(true)))
            },
        )
        home = response["codexHome"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: protocolFailure()
        }
        wire.write(json("method" to "initialized".json()))
    }

    /** Shared by user operations and background polling; per-request diagnostics belong to trace. */
    @HighFrequency
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        failure?.let { throw it }
        val id = (++nextId).toString()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        log.v { "Codex request method=$method" }
        try {
            wire.write(json("id" to id.json(), "method" to method.json(), "params" to params))
            return withTimeoutOrNull(REQUEST_TIMEOUT) { result.await() }
                ?: fail(EngineFailure.Transport(TransportFailureReason.Timeout))
        } finally {
            pending.remove(id)
        }
    }

    suspend fun respond(id: JsonElement, result: JsonObject) {
        failure?.let { throw it }
        log.i { "Codex permission response" }
        wire.write(json("id" to id, "result" to result))
    }

    suspend fun reject(id: JsonElement) {
        // A failed connection has no one left to answer.
        if (failure != null) return
        log.i { "Codex rejected server request" }
        wire.write(
            json(
                "id" to id,
                "error" to json(
                    "code" to JsonPrimitive(METHOD_NOT_FOUND),
                    "message" to "Unsupported client request".json(),
                ),
            ),
        )
    }

    private fun receive(message: JsonObject) {
        if (message.text("method") != null) {
            if (!events.trySend(
                    message,
                ).isSuccess
            ) {
                shutdown(
                    EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation)),
                )
            }
        } else {
            val result = pending[message.text("id")] ?: return
            if (message["error"] != null) {
                result.completeExceptionally(EngineException(EngineFailure.Request(RequestFailureReason.Invalid)))
            } else {
                result.complete(message.obj("result"))
            }
        }
    }

    private fun shutdown(error: EngineException) {
        if (failure != null) return
        failure = error
        pending.values.toList().forEach { it.completeExceptionally(error) }
        events.close(error)
        wire.close()
    }

    override fun close() {
        shutdown(EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)))
        reader.cancel()
    }

    private companion object {
        const val METHOD_NOT_FOUND = -32601
        const val REQUEST_TIMEOUT = 30_000L
        const val EVENT_CAPACITY = 1024
    }
}
