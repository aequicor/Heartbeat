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
            val cleanup = profile.onClose { process.destroyForcibly() }
            ProcessCodexWire(process, dispatchers) { cleanup.dispose() }
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
        process.destroyForcibly()
        release()
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
