package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Routes SDK control frames independently of interactive permissions. One exchange owns one user turn.
 * The caller validates session identity and supplies native hook/permission handlers; no payload is logged.
 * Top-level result revokes handlers before stdin EOF. Process lifetime and pipe teardown belong to transport.
 */
internal class ClaudeControlExchange(
    private val duplex: ClaudeDuplex,
    private val handle: suspend (JsonObject) -> JsonObject,
    private val observe: suspend (JsonObject) -> Unit,
) {
    private val log = Log.tag("ClaudeControlExchange")
    private val lock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private val handlers = mutableMapOf<String, Job>()
    private val seen = mutableSetOf<String>()
    private val events = Channel<JsonObject>(MAX_CONTROL_EVENTS)
    private var isEnded = false

    suspend fun run(initialization: JsonObject, user: JsonObject) = coroutineScope {
        val observer = launch { for (frame in events) observe(frame) }
        val reader = launch { read(this) }
        try {
            request(initialization)
            synchronized(lock) { if (isEnded) protocolFailure() }
            duplex.send(user)
            reader.join()
            observer.join()
        } finally {
            revoke()
            reader.cancel()
            observer.cancel()
        }
    }

    private suspend fun request(body: JsonObject): JsonObject {
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<JsonObject>()
        synchronized(lock) {
            if (isEnded) protocolFailure()
            pending[id] = answer
        }
        return try {
            withTimeout(INITIALIZE_TIMEOUT_MS) {
                duplex.send(
                    buildJsonObject {
                        put("type", "control_request")
                        put("request_id", id)
                        put("request", body)
                    },
                )
                answer.await()
            }
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    private suspend fun read(scope: CoroutineScope) {
        try {
            while (true) {
                val frame = duplex.receive() ?: break
                when (frame.text("type")) {
                    "control_response" -> response(frame)

                    "control_request" -> dispatch(scope, frame)

                    "control_cancel_request" -> withdraw(frame)

                    else -> {
                        val isResult = frame.text("type") == "result" &&
                            (frame["parent_tool_use_id"] == null || frame["parent_tool_use_id"] == JsonNull)
                        if (isResult) revoke()
                        if (events.trySend(frame).isFailure) protocolFailure()
                        if (isResult) duplex.closeInput()
                    }
                }
            }
        } finally {
            revoke()
            events.close()
        }
    }

    private fun response(frame: JsonObject) {
        val response = frame["response"] as? JsonObject ?: protocolFailure()
        val id = response.text("request_id") ?: protocolFailure()
        val answer = synchronized(lock) { pending.remove(id) } ?: protocolFailure()
        when (response.text("subtype")) {
            "success" -> answer.complete(response["response"] as? JsonObject ?: buildJsonObject {})
            "error" -> answer.completeExceptionally(controlFailure())
            else -> protocolFailure()
        }
    }

    private fun dispatch(scope: CoroutineScope, frame: JsonObject) {
        val id = frame.text("request_id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }
            ?: protocolFailure()
        val body = frame["request"] as? JsonObject ?: protocolFailure()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                answer(id, body)
            } finally {
                synchronized(lock) { handlers.remove(id) }
            }
        }
        synchronized(lock) {
            val isFull = handlers.size >= MAX_HANDLERS || seen.size >= MAX_REQUESTS
            if (isEnded || isFull || !seen.add(id)) {
                job.cancel()
                protocolFailure()
            }
            handlers[id] = job
        }
        job.start()
    }

    private suspend fun answer(id: String, body: JsonObject) {
        val result = try {
            val output = handle(body)
            buildJsonObject {
                put("subtype", "success")
                put("request_id", id)
                put("response", output)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redacted()) { "Claude control request failed" }
            buildJsonObject {
                put("subtype", "error")
                put("request_id", id)
                put("error", "Unsupported or invalid control request")
            }
        }
        currentCoroutineContext().ensureActive()
        duplex.send(
            buildJsonObject {
                put("type", "control_response")
                put("response", result)
            },
        )
    }

    private fun withdraw(frame: JsonObject) {
        val id = frame.text("request_id") ?: protocolFailure()
        synchronized(lock) { handlers.remove(id) }?.cancel()
    }

    private fun revoke() {
        val jobs = synchronized(lock) {
            isEnded = true
            pending.values.forEach { it.completeExceptionally(controlFailure()) }
            pending.clear()
            handlers.values.toList().also { handlers.clear() }
        }
        jobs.forEach { it.cancel() }
    }
}

private fun controlFailure(): Exception = IllegalStateException(
    "Claude control channel ended or rejected initialization",
)
private const val INITIALIZE_TIMEOUT_MS = 60_000L
private const val MAX_HANDLERS = 32
private const val MAX_REQUESTS = 4_096
private const val MAX_ID_CHARS = 256

private const val MAX_CONTROL_EVENTS = 64
