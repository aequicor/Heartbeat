package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@ContributesBinding(ProfileScope::class)
@Inject
internal class LocalCodexTransport(
    private val config: CodexLocalConfiguration,
    private val launches: EngineLaunchConfig,
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

    override suspend fun open(): CodexWire = open(launches.context(CodexEngine.Id))

    override suspend fun open(launch: LaunchContext): CodexWire = withContext(dispatchers.io) {
        val resolved = resolveCodexLaunch(launch, config)
        log.i { "Starting local Codex app-server source=${resolved.source}" }
        try {
            // A missing CLI still tries the bare command, so it fails as an unavailable app-server, as before.
            require(resolved.isRunnable || resolved.source == InstallSource.Missing) {
                "Codex executable cannot be started safely"
            }
            resolved.home?.let { require(File(it).isAbsolute) { "Codex home must be absolute" } }
            val builder = ProcessBuilder(codexCommand(resolved)).redirectError(ProcessBuilder.Redirect.DISCARD)
            applyCodexEnvironment(builder.environment(), resolved)
            val process = builder.start()
            var cleanup: (() -> Unit)? = null
            val wire = ProcessCodexWire(process, dispatchers) { cleanup?.invoke() }
            val handle = profile.onClose { wire.close() }
            cleanup = { handle.dispose() }
            wire
        } catch (e: IOException) {
            throw e.sanitized()
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Codex launch settings rejected" }
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
    }

    override suspend fun locate(launch: LaunchContext): Installation = withContext(dispatchers.io) {
        val resolved = resolveCodexLaunch(launch, config)
        val version = if (resolved.isRunnable && resolved.source != InstallSource.Missing) version(resolved) else null
        log.i { "Codex located source=${resolved.source} version=${version ?: "unknown"}" }
        Installation(
            source = resolved.source,
            version = version,
            path = resolved.executable.takeIf { resolved.source != InstallSource.Missing },
            isRunnable = resolved.isRunnable,
        )
    }

    override suspend fun check(settings: LaunchSettings): List<LaunchProblem> =
        withContext(dispatchers.io) { codexLaunchProblems(settings) }

    override fun releaseTarget(): CodexTarget? = codexReleaseTarget()

    /** `codex --version`, bounded and cancellable; an executable that does not answer has no known version. */
    private suspend fun version(launch: CodexLaunch): String? = try {
        val builder = ProcessBuilder(launch.executable, "--version").redirectErrorStream(true)
        applyCodexEnvironment(builder.environment(), launch)
        val process = builder.start()
        try {
            process.outputStream.close()
            val isDone = runInterruptible { process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            if (isDone) parseCodexVersion(process.inputStream.readNBytes(MAX_VERSION_BYTES).decodeToString()) else null
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    } catch (e: IOException) {
        // The message names the executable's path; only the kind of failure is logged.
        log.w(e.sanitized()) { "Codex version could not be read" }
        null
    }

    private companion object {
        const val VERSION_TIMEOUT_SECONDS = 15L
        const val MAX_VERSION_BYTES = 4096
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
        try {
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
        } finally {
            // A close that raced this write could not take the lock; the write releases stdin once it is done.
            if (isClosed.get()) closeStdin()
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

    /**
     * Closing flushes under the writer lock. Process death does not release the pipe while a descendant still holds
     * it, so when an in-flight write holds the lock, that write closes stdin after releasing it: [isClosed] is set
     * before this lock attempt, and [write] re-checks it after unlocking, so one of them always closes the writer.
     */
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
