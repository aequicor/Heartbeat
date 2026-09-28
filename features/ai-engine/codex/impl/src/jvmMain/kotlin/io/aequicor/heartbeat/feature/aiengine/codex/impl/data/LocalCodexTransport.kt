package io.aequicor.heartbeat.feature.aiengine.codex.impl.data
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@ContributesBinding(ProfileScope::class)
@Inject
internal class LocalCodexTransport(
    private val config: CodexLocalConfiguration,
    private val dispatchers: DispatcherProvider,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : CodexTransport {
    private val log = Log.tag("LocalCodexTransport")

    override suspend fun available(): EngineAvailability = withContext(dispatchers.main) {
        try {
            coroutineScope {
                val rpc = CodexRpc(open(), this)
                try {
                    rpc.initialize()
                    EngineAvailability.Available
                } finally {
                    rpc.close()
                }
            }
        } catch (e: EngineException) {
            log.w(e) { "Codex installation probe failed" }
            EngineAvailability.Unavailable(e.failure)
        }
    }

    override suspend fun open(): CodexWire = withContext(dispatchers.io) {
        log.i { "Starting local Codex app-server" }
        try {
            val command = listOf(config.executable, "app-server", "-c", "model_provider=\"openai\"")
            val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().apply {
                remove("OPENAI_API_KEY")
                remove("CODEX_API_KEY")
                remove("OPENAI_BASE_URL")
                if (config.homeDirectory != null) put("CODEX_HOME", config.homeDirectory)
            }
            config.homeDirectory?.let { require(File(it).isAbsolute) { "Codex home must be absolute" } }
            require(!config.executable.endsWith(".cmd", true) && !config.executable.endsWith(".bat", true))
            val process = builder.start()
            var cleanup: (() -> Unit)? = null
            val wire = ProcessCodexWire(process, dispatchers) { cleanup?.invoke() }
            val handle = profile.onClose { wire.close() }
            cleanup = { handle.dispose() }
            wire
        } catch (e: IOException) {
            throw e.sanitized()
        }
    }
}

internal class ProcessCodexWire(
    private val process: Process,
    private val dispatchers: DispatcherProvider,
    private val release: () -> Unit,
) : CodexWire {
    private val log = Log.tag("ProcessCodexWire")
    private val isClosed = AtomicBoolean(false)
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val writes = Mutex()
    override val messages = flow {
        try {
            val input = process.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = withContext(dispatchers.io) { input.readLine() } ?: break
                val value = try {
                    Json.parseToJsonElement(line) as? JsonObject ?: protocolFailure()
                } catch (e: IllegalArgumentException) {
                    throw e.sanitized()
                }
                emit(value)
            }
        } catch (e: IOException) {
            throw e.sanitized()
        }
    }

    override suspend fun write(message: JsonObject) {
        writes.withLock {
            withContext(dispatchers.io) {
                try {
                    writer.write(message.toString())
                    writer.newLine()
                    writer.flush()
                } catch (e: IOException) {
                    throw e.sanitized()
                }
            }
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        log.i { "Stopping local Codex app-server" }
        // Destroy first: blocked pipe IO must unblock before stdin can be closed.
        destroyDescendants()
        process.destroyForcibly()
        closeStdin()
        release()
    }

    /**
     * Wrapper launchers leave the real app-server as a child that inherits our pipes, so descendants alive at close
     * are stopped before the parent. Failure to enumerate them must not prevent stopping the parent.
     */
    private fun destroyDescendants() {
        try {
            process.descendants().toList().forEach { it.destroyForcibly() }
        } catch (e: SecurityException) {
            log.w(e) { "Codex app-server descendants unavailable" }
        } catch (e: UnsupportedOperationException) {
            log.w(e) { "Codex app-server descendants unsupported" }
        }
    }

    /** Closing flushes under the writer lock; while a write holds it, process death already releases the pipe. */
    private fun closeStdin() {
        if (!writes.tryLock()) return
        try {
            writer.close()
        } catch (e: IOException) {
            log.w(e) { "Codex app-server stdin close failed" }
        } finally {
            writes.unlock()
        }
    }
}

/** Native diagnostics can contain paths or response bodies; never retain the native exception. */
private fun Exception.sanitized(): EngineException = EngineException(
    EngineFailure.Transport(
        if (this is IOException) {
            TransportFailureReason.ServiceUnavailable
        } else {
            TransportFailureReason.ProtocolViolation
        },
    ),
)
