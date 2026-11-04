package io.aequicor.heartbeat.feature.aiengine.codex.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexProbeEnvironment
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexRpc
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.ProcessCodexWire
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.codexProcessArguments
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.json
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.time.TimeMark

/** Outcome of one restricted baseline check: confirmed by observation, conclusively failed, or never observed. */
internal enum class CodexBaselineOutcome { Confirmed, Rejected, Unobserved }

/** What the clean-home baseline observed; an unobserved check is never a pass. */
internal data class CodexBaselineReceipt(
    val initialize: CodexBaselineOutcome,
    val configRead: CodexBaselineOutcome,
    /** Version the probed app-server reported in its own handshake; null when it never completed. */
    val version: String? = null,
) {
    val hasRejectedCheck: Boolean get() = initialize == CodexBaselineOutcome.Rejected ||
        configRead == CodexBaselineOutcome.Rejected
}

/**
 * Clean-home baseline of one exact Codex executable. It starts the adapter's app-server in a temporary
 * `CODEX_HOME` with only the inert probe environment, performs the `initialize` handshake and reads
 * `config/read` with its layers. No credentials, user home, project configuration, tools or history are read or
 * written, and no thread, turn or model request is started. The reported version is the one the process itself
 * announced in the handshake, never a `--version` probe.
 */
internal fun interface CodexBaselineProbe {
    suspend fun run(executable: Path, deadline: TimeMark): CodexBaselineReceipt
}

internal class JvmCodexBaselineProbe(private val dispatchers: DispatcherProvider) : CodexBaselineProbe {
    private val log = Log.tag("CodexBaselineProbe")

    override suspend fun run(executable: Path, deadline: TimeMark): CodexBaselineReceipt {
        if (deadline.hasPassedNow()) {
            return CodexBaselineReceipt(CodexBaselineOutcome.Unobserved, CodexBaselineOutcome.Unobserved)
        }
        return try {
            probe(executable, deadline)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Codex admission baseline could not start" }
            CodexBaselineReceipt(CodexBaselineOutcome.Rejected, CodexBaselineOutcome.Unobserved)
        } catch (e: SecurityException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Codex admission baseline could not start" }
            CodexBaselineReceipt(CodexBaselineOutcome.Rejected, CodexBaselineOutcome.Unobserved)
        }
    }

    private suspend fun probe(executable: Path, deadline: TimeMark): CodexBaselineReceipt {
        val directory = withContext(dispatchers.io) { Files.createTempDirectory("codex-admission-") }
        val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        var process: Process? = null
        var connection: CodexRpc? = null
        try {
            val home = withContext(dispatchers.io) { Files.createDirectory(directory.resolve("home")) }
            val work = withContext(dispatchers.io) { Files.createDirectory(directory.resolve("work")) }
            val started = withContext(NonCancellable) { start(executable, work, home) }
            process = started
            started.onExit().whenComplete { _, _ -> deleteTree(directory) }
            val rpc = CodexRpc(ProcessCodexWire(started, dispatchers) {}, scope)
            connection = rpc
            val initialize = initialize(rpc, deadline)
            val configRead = if (initialize == CodexBaselineOutcome.Confirmed) {
                configRead(rpc, deadline)
            } else {
                CodexBaselineOutcome.Unobserved
            }
            return CodexBaselineReceipt(
                initialize,
                configRead,
                version = if (initialize == CodexBaselineOutcome.Confirmed) rpc.engineVersion else null,
            )
        } finally {
            withContext(NonCancellable) {
                connection?.close()
                process?.let(::awaitExit)
                scope.cancel()
            }
        }
    }

    private fun start(executable: Path, work: Path, home: Path): Process {
        val builder = ProcessBuilder(codexProcessArguments(codexBaselineArguments(executable)))
            .directory(work.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val environment = builder.environment()
        val retained = environment.filterKeys { it.uppercase() in CodexProbeEnvironment }
        environment.clear()
        environment.putAll(retained)
        environment["CODEX_HOME"] = home.toString()
        return builder.start()
    }

    private suspend fun initialize(rpc: CodexRpc, deadline: TimeMark): CodexBaselineOutcome {
        val remaining = remainingMillis(deadline) ?: return CodexBaselineOutcome.Unobserved
        return try {
            val isCompleted = withTimeoutOrNull(remaining) {
                rpc.initialize()
                true
            }
            if (isCompleted == true) CodexBaselineOutcome.Confirmed else CodexBaselineOutcome.Unobserved
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex admission handshake failed" }
            if (e.failure == EngineFailure.Transport(TransportFailureReason.Timeout)) {
                CodexBaselineOutcome.Unobserved
            } else {
                CodexBaselineOutcome.Rejected
            }
        } catch (e: IOException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Codex admission handshake failed" }
            CodexBaselineOutcome.Rejected
        }
    }

    private suspend fun configRead(rpc: CodexRpc, deadline: TimeMark): CodexBaselineOutcome {
        val remaining = remainingMillis(deadline) ?: return CodexBaselineOutcome.Unobserved
        return try {
            val response = withTimeoutOrNull(remaining) {
                rpc.request("config/read", json("includeLayers" to JsonPrimitive(true)))
            }
            when {
                response == null -> CodexBaselineOutcome.Unobserved
                response["config"] is JsonObject && response["layers"] is JsonArray -> CodexBaselineOutcome.Confirmed
                else -> CodexBaselineOutcome.Rejected
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex admission config read failed" }
            if (e.failure == EngineFailure.Transport(TransportFailureReason.Timeout)) {
                CodexBaselineOutcome.Unobserved
            } else {
                CodexBaselineOutcome.Rejected
            }
        } catch (e: IOException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Codex admission config read failed" }
            CodexBaselineOutcome.Rejected
        }
    }

    private fun remainingMillis(deadline: TimeMark): Long? {
        if (deadline.hasPassedNow()) return null
        return (-deadline.elapsedNow()).inWholeMilliseconds.coerceIn(1L, COMMAND_TIMEOUT_MILLIS)
    }

    private fun awaitExit(process: Process) {
        if (!process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.w { "Codex admission process exit timed out" }
        }
        try {
            process.inputStream.close()
            process.errorStream.close()
        } catch (e: IOException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Codex admission process streams not released" }
        }
    }

    private fun deleteTree(directory: Path) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        } catch (e: IOException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Codex admission directory cleanup failed" }
        } catch (e: SecurityException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Codex admission directory cleanup was denied" }
        }
    }
}

/** Only `app-server` is started: the clean home and inert environment keep the process credential-free. */
internal fun codexBaselineArguments(executable: Path): List<String> = listOf(executable.toString(), "app-server")

private const val COMMAND_TIMEOUT_MILLIS = 30_000L
private const val EXIT_TIMEOUT_SECONDS = 5L
