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
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Transport to one native Pi process; see Pi `docs/rpc.md` for the JSONL protocol. */
internal interface PiConnection {
    /** False once the process exited, the reader failed or [close] was called. */
    val isOpen: Boolean

    /** Working directory of the process: the only place where trusted file edits are applied. */
    val workingDirectory: Path?

    /** Explicit limits from this process's models.json; excludes Pi's synthetic fallback windows. */
    val contextWindows: Map<String, Long>

    /** Sends a correlated command and returns its `data`; a timeout fails only this command. */
    suspend fun command(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonObject

    /** Writes an uncorrelated record, such as an `extension_ui_response`. */
    suspend fun send(record: JsonObject)

    /** Requests termination; idempotent. A closed connection does not prove its process exited. */
    fun close()

    /** Requests termination and waits for exit of this process and observed descendants; false is unconfirmed. */
    suspend fun stopAndAwait(): Boolean
}

/**
 * One continuously drained JSONL connection; native diagnostics never escape into logs.
 * Unparseable stdout lines and failing event consumers are skipped so the pipe keeps draining.
 * A command timeout never terminates the process: an accepted native turn may still be running.
 */
internal class PiRpc(
    private val process: Process,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val event: suspend (JsonObject) -> Unit,
    private val failed: suspend (EngineFailure) -> Unit,
    private val commandTimeoutMillis: Long = COMMAND_TIMEOUT,
    override val workingDirectory: Path? = null,
    override val contextWindows: Map<String, Long> = emptyMap(),
) : PiConnection {
    private val log = Log.tag("PiRpc")
    private val termination = PiProcessStop(process, dispatchers)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val writes = Mutex()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)

    private val closed = AtomicBoolean(false)
    private val isClosed get() = closed.get()

    @Volatile private var closeRegistration: DisposableHandle? = null

    override val isOpen: Boolean get() = !isClosed && process.isAlive

    init {
        scope.launch(dispatchers.io) { read() }
        scope.launch(dispatchers.io) { drainDiagnostics() }
    }

    /** Ties the process to its owner scope; the registration is disposed when this connection closes. */
    fun closeWith(registration: DisposableHandle) {
        closeRegistration = registration
        if (isClosed) registration.dispose()
    }

    override suspend fun command(type: String, fields: JsonObject): JsonObject {
        if (!isOpen) piFailure(EngineFailure.Engine(EngineFailureReason.Crashed))
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        // close() may have drained pending between the first check and registration.
        if (isClosed) result.completeExceptionally(EngineException(EngineFailure.Engine(EngineFailureReason.Crashed)))
        try {
            if (type == "get_state") {
                log.v { "Pi command: $type" }
            } else {
                log.d { "Pi command: $type" }
            }
            write(JsonObject(fields + mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type))))
            val response = withTimeoutOrNull(commandTimeoutMillis) { result.await() } ?: run {
                log.w(EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))) {
                    "Pi command timed out: $type"
                }
                piFailure(EngineFailure.Transport(TransportFailureReason.Timeout))
            }
            if ((response["success"] as? JsonPrimitive)?.booleanOrNull != true) {
                piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
            }
            return response["data"] as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun send(record: JsonObject) {
        if (!isOpen) piFailure(EngineFailure.Engine(EngineFailureReason.Crashed))
        log.d { "Pi record: ${record.string("type").orEmpty()}" }
        write(record)
    }

    override fun close() {
        terminate(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    override suspend fun stopAndAwait(): Boolean {
        close()
        return termination.awaitStopped()
    }

    /** Fails in-flight commands with [failure], then requests process termination before closing its input. */
    private fun terminate(failure: EngineFailure): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        closeRegistration?.dispose()
        pending.values.forEach { it.completeExceptionally(EngineException(failure)) }
        pending.clear()
        termination.request()
        // Closing a BufferedWriter can wait for an in-flight write or flush. It must never block the stop
        // caller, especially after a refused kill. Cleanup starts only after exit, on the injected IO executor.
        process.onExit().whenCompleteAsync({ _, _ -> closeWriter() }, dispatchers.io.asExecutor())
        return true
    }

    private fun closeWriter() {
        try {
            writer.close()
        } catch (e: IOException) {
            log.w(e) { "Pi input already closed" }
        }
    }

    private suspend fun write(record: JsonObject) {
        try {
            writes.withLock {
                withContext(dispatchers.io) {
                    writer.write(record.toString())
                    writer.write("\n")
                    writer.flush()
                }
            }
        } catch (e: IOException) {
            log.w(e) { "Pi input stream failed" }
            piFailure(EngineFailure.Engine(EngineFailureReason.Crashed))
        }
    }

    private suspend fun read() {
        var failure: EngineFailure = EngineFailure.Engine(EngineFailureReason.Crashed)
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!isClosed) {
                    val line = reader.readLine() ?: break
                    dispatch(line)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Pi protocol reader failed" }
            failure = EngineFailure.Transport(TransportFailureReason.ProtocolViolation)
        }
        if (terminate(failure)) failed(failure)
    }

    private suspend fun dispatch(line: String) {
        val record = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (e: SerializationException) {
            // Parser messages quote the offending input, which may contain user data.
            log.w(EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))) {
                "Skipping malformed Pi record: ${e::class.simpleName.orEmpty()}"
            }
            null
        } ?: return
        if (record.string("type") == "response") {
            record.string("id")?.let { pending.remove(it)?.complete(record) }
            return
        }
        try {
            event(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Pi event consumer failed: ${record.string("type").orEmpty()}" }
        }
    }

    private fun drainDiagnostics() {
        try {
            process.errorStream.use { stream ->
                val buffer = ByteArray(DIAGNOSTIC_BUFFER)
                while (stream.read(buffer) >= 0) { /* Drain diagnostics without exposing user data. */ }
            }
        } catch (e: IOException) {
            log.w(e) { "Pi diagnostic stream closed" }
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT = 30_000L
        const val DIAGNOSTIC_BUFFER = 4096
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Resolves only an explicit limit for this process and exact provider/model route. */
internal fun PiConnection.contextCapacity(model: JsonObject?): Long? {
    val provider = model?.string("provider") ?: return null
    val id = model.string("id") ?: return null
    return contextWindows["$provider/$id"]
}
