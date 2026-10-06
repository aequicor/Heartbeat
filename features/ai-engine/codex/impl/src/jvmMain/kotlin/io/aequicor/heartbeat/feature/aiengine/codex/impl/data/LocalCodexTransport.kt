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

    override suspend fun prepare(): PreparedCodexLaunch {
        val context = launches.context(CodexEngine.Id)
        return withContext(dispatchers.io) {
            val resolved = resolveCodexLaunch(context, config).let {
                it.copy(overrides = it.overrides.toList(), environment = it.environment.toList())
            }
            val environment = capturedCodexEnvironment(resolved)
            object : PreparedCodexLaunch {
                override suspend fun open(): CodexWire = openResolved(resolved, environment)
                override suspend fun open(off: CodexNativeOff): CodexWire {
                    if (off.isRestricted) off.requireVersion(version())
                    return openResolved(resolved, environment, off)
                }

                override suspend fun version(): String? = withContext(dispatchers.io) {
                    if (resolved.isRunnable && resolved.source != InstallSource.Missing) {
                        version(resolved, environment)
                    } else {
                        null
                    }
                }
            }
        }
    }

    override suspend fun open(): CodexWire = prepare().open()

    override suspend fun open(launch: LaunchContext): CodexWire {
        val resolved = withContext(dispatchers.io) { resolveCodexLaunch(launch, config) }
        return openResolved(resolved, capturedCodexEnvironment(resolved))
    }

    /** Cancellation during dispatcher handoff still closes the newly started process. */
    private suspend fun openResolved(
        resolved: CodexLaunch,
        environment: Map<String, String>,
        off: CodexNativeOff = CodexNativeOff(),
    ): CodexWire {
        var owned: CodexWire? = null
        var isTransferred = false
        return try {
            val wire = withContext(dispatchers.io) {
                startResolved(resolved, environment, off).also { owned = it }
            }
            isTransferred = true
            wire
        } finally {
            if (!isTransferred) owned?.close()
        }
    }

    /** Blocking process creation; callers dispatch it to IO before entering. */
    private fun startResolved(resolved: CodexLaunch, environment: Map<String, String>, off: CodexNativeOff): CodexWire {
        log.i { "Starting local Codex app-server source=${resolved.source}" }
        return try {
            require(resolved.isRunnable && resolved.source != InstallSource.Missing) {
                "Codex executable cannot be started safely"
            }
            resolved.home?.let { require(File(it).isAbsolute) { "Codex home must be absolute" } }
            val builder = ProcessBuilder(codexProcessArguments(codexCommand(resolved, off)))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().clear()
            builder.environment().putAll(environment)
            val process = builder.start()
            var cleanup: (() -> Unit)? = null
            val wire = ProcessCodexWire(process, dispatchers) { cleanup?.invoke() }
            val handle = profile.onClose { wire.close() }
            cleanup = { handle.dispose() }
            wire
        } catch (e: IOException) {
            // Installation failures are distinct from network failures.
            // Do not retain native diagnostics: they can contain the user's executable path.
            val failure = EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
            log.w(e.sanitized()) { "Codex executable could not be started" }
            throw failure
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
    private suspend fun version(
        launch: CodexLaunch,
        environment: Map<String, String> = capturedCodexEnvironment(launch),
    ): String? = try {
        val builder = ProcessBuilder(launch.executable, "--version").redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(environment)
        val process = builder.start()
        try {
            process.outputStream.close()
            val isDone = runInterruptible { process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            if (isDone) parseCodexVersion(probeOutput(process)) else null
        } finally {
            stopProbe(process)
        }
    } catch (e: IOException) {
        // The message names the executable's path; only the kind of failure is logged.
        log.w(e.sanitized()) { "Codex version could not be read" }
        null
    }

    /** Reads only buffered bytes; launcher children can keep stdout open after their parent exits. */
    private suspend fun probeOutput(process: Process): String = runInterruptible {
        process.inputStream.readNBytes(process.inputStream.available().coerceAtMost(MAX_VERSION_BYTES)).decodeToString()
    }

    private fun stopProbe(process: Process) {
        try {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        } catch (e: SecurityException) {
            log.w(e.sanitized()) { "Version probe descendants unavailable" }
        } catch (e: UnsupportedOperationException) {
            log.w(e.sanitized()) { "Version probe descendants unsupported" }
        } finally {
            try {
                process.destroyForcibly()
            } finally {
                closeProbeOutput(process)
            }
        }
    }

    private fun closeProbeOutput(process: Process) {
        try {
            process.inputStream.close()
        } catch (e: IOException) {
            log.w(e.sanitized()) { "Version probe output could not be closed" }
        }
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

/** Captures inherited entries as well as overrides; subsequent launches cannot drift with profile settings. */
private fun capturedCodexEnvironment(launch: CodexLaunch): Map<String, String> =
    System.getenv().toMutableMap().apply { applyCodexEnvironment(this, launch) }.toMap()
