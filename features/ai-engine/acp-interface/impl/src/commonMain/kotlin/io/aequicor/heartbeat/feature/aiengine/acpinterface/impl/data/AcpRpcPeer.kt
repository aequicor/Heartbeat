package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class AcpRpcPeer(
    private val transport: AcpTransport,
    private val scope: CoroutineScope,
    private val onNotification: suspend (String, JsonObject) -> Unit,
    private val onRequest: suspend (JsonElement, String, JsonObject) -> Unit,
) {
    private val log = Log.tag("AcpRpcPeer")
    private val state = Mutex()
    private val writes = Mutex()
    private val pending = mutableMapOf<JsonElement, CompletableDeferred<JsonObject>>()
    private var nextId = 0L
    private var isClosed = false

    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    val frame = transport.receive() ?: break
                    dispatch(acpJson.parseToJsonElement(frame) as? JsonObject ?: throw AcpException.Protocol())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(AcpDiagnostic(e)) { "ACP reader failed" }
            } finally {
                withContext(NonCancellable) { close() }
            }
        }
    }

    suspend fun request(method: String, params: JsonObject): JsonObject = submit(method, params).await()

    suspend fun submit(method: String, params: JsonObject): Deferred<JsonObject> {
        val result = CompletableDeferred<JsonObject>()
        val id = state.withLock {
            ensureOpen()
            JsonPrimitive(++nextId).also { pending[it] = result }
        }
        write(fields("id" to id, "method" to JsonPrimitive(method), "params" to params))
        return result
    }

    suspend fun notify(method: String, params: JsonObject) {
        write(fields("method" to JsonPrimitive(method), "params" to params))
    }

    suspend fun respond(id: JsonElement, result: JsonObject) {
        write(fields("id" to id, "result" to result))
    }

    suspend fun reject(id: JsonElement, code: Int) {
        write(
            fields(
                "id" to id,
                "error" to fields("code" to JsonPrimitive(code), "message" to JsonPrimitive("Request rejected")),
            ),
        )
    }

    suspend fun close() {
        val waiting = state.withLock {
            if (isClosed) return
            isClosed = true
            pending.values.toList().also { pending.clear() }
        }
        log.i { "ACP connection closed" }
        waiting.forEach { it.completeExceptionally(AcpException.Disconnected()) }
        transport.close()
        scope.cancel()
    }

    private suspend fun write(body: JsonObject) {
        try {
            writes.withLock {
                state.withLock { ensureOpen() }
                log.d { "ACP write frame" }
                transport.send(JsonObject(body + ("jsonrpc" to JsonPrimitive("2.0"))).toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(AcpDiagnostic(e)) { "ACP write failed" }
            withContext(NonCancellable) { close() }
            throw AcpException.Disconnected()
        }
    }

    private suspend fun dispatch(message: JsonObject) {
        if (message.string("jsonrpc") != "2.0") throw AcpException.Protocol()
        val id = message["id"]
        if ("method" in message) {
            validateRequest(message)
            val method = message.string("method")
            val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
            if (id == null) {
                onNotification(method, params)
            } else {
                validateId(id)
                onRequest(id, method, params)
            }
        } else {
            if (id == null) throw AcpException.Protocol()
            validateId(id)
            complete(id, message)
        }
    }

    private suspend fun complete(id: JsonElement, message: JsonObject) {
        if (("result" in message) == ("error" in message)) throw AcpException.Protocol()
        val result = state.withLock { pending.remove(id) } ?: throw AcpException.Protocol()
        if ("error" in message) {
            val error = message.obj("error")
            result.completeExceptionally(
                AcpException.Remote(error.number("code"), error.string("message"), error["data"]),
            )
        } else {
            result.complete(message.obj("result"))
        }
    }

    private fun ensureOpen() {
        if (isClosed) throw AcpException.Disconnected()
    }

    private fun validateRequest(message: JsonObject) {
        if ("result" in message || "error" in message) throw AcpException.Protocol()
        val params = message["params"]
        if (params != null && params !is JsonObject) throw AcpException.Protocol()
    }

    private fun validateId(id: JsonElement) {
        if (id !is JsonPrimitive || id == JsonNull) throw AcpException.Protocol()
        if (!id.isString && id.content.toLongOrNull() == null) throw AcpException.Protocol()
    }
}
