package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface PiConnection {
    suspend fun command(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonObject
    fun close()
}

/** One continuously drained JSONL connection; native diagnostics never escape into logs. */
internal class PiRpc(
    private val process: Process,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val event: suspend (JsonObject) -> Unit,
    private val failed: suspend (EngineFailure) -> Unit,
) : PiConnection {
    private val log = Log.tag("PiRpc")
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val writes = Mutex()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)

    @Volatile private var isClosed = false

    init {
        scope.launch(dispatchers.io) {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val record = Json.parseToJsonElement(line).jsonObject
                        if (record.string("type") == "response") {
                            record.string("id")?.let { pending.remove(it)?.complete(record) }
                        } else {
                            event(record)
                        }
                    }
                }
                if (!isClosed) fail(EngineFailure.Engine(EngineFailureReason.Crashed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))) {
                    "Pi protocol reader failed: ${e::class.simpleName.orEmpty()}"
                }
                fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
            }
        }
        scope.launch(dispatchers.io) {
            try {
                process.errorStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (stream.read(buffer) >= 0) { /* Drain diagnostics without exposing user data. */ }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(EngineException(EngineFailure.Engine(EngineFailureReason.Crashed))) {
                    "Pi diagnostic stream closed: ${e::class.simpleName.orEmpty()}"
                }
            }
        }
    }

    override suspend fun command(type: String, fields: JsonObject): JsonObject {
        if (isClosed) piFailure(EngineFailure.Engine(EngineFailureReason.Crashed))
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        val deadline = scope.launch(dispatchers.default) {
            delay(COMMAND_TIMEOUT)
            result.completeExceptionally(EngineException(EngineFailure.Transport(TransportFailureReason.Timeout)))
            close()
        }
        try {
            log.d { "Pi command: $type" }
            writes.withLock {
                withContext(dispatchers.io) {
                    writer.write(
                        JsonObject(fields + mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type))).toString(),
                    )
                    writer.write("\n")
                    writer.flush()
                }
            }
            val response = result.await()
            if (response["success"]?.jsonPrimitive?.booleanOrNull != true) {
                piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
            }
            return response["data"] as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            log.w(EngineException(EngineFailure.Engine(EngineFailureReason.Crashed))) {
                "Pi command transport failed: ${e::class.simpleName.orEmpty()}"
            }
            piFailure(EngineFailure.Engine(EngineFailureReason.Crashed))
        } finally {
            deadline.cancel()
            pending.remove(id)
        }
    }

    private suspend fun fail(failure: EngineFailure) {
        pending.values.forEach { it.completeExceptionally(EngineException(failure)) }
        pending.clear()
        failed(failure)
    }

    override fun close() {
        isClosed = true
        pending.values.forEach {
            it.completeExceptionally(EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)))
        }
        pending.clear()
        val descendants = process.descendants().use { it.toList() }
        descendants.asReversed().forEach { it.destroyForcibly() }
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    private companion object {
        const val COMMAND_TIMEOUT = 30_000L
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
