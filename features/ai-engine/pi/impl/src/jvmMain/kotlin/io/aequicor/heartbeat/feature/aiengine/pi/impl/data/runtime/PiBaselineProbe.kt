package io.aequicor.heartbeat.feature.aiengine.pi.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRpc
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.piCatalogProbeJson
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.probeCommand
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.retainPiEnvironment
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.withoutDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.time.TimeMark

/** Outcome of one restricted baseline check: confirmed by observation, conclusively failed, or never observed. */
internal enum class PiBaselineOutcome { Confirmed, Rejected, Unobserved }

/** What the socket-free baseline observed; an unobserved check is never a pass. */
internal data class PiBaselineReceipt(val launch: PiBaselineOutcome, val catalog: PiBaselineOutcome) {
    val hasRejectedCheck: Boolean get() = launch == PiBaselineOutcome.Rejected || catalog == PiBaselineOutcome.Rejected
}

/**
 * Socket-free baseline of one exact Pi executable. It reuses the catalog probe the Pi module already runs: an
 * isolated `--mode rpc` process with `PI_OFFLINE=1`, no session/extensions/skills/templates/context/themes, a
 * placeholder catalog key and only `get_available_models`. No credentials, user home, project, tools or history
 * are read or written, and no model request leaves the machine.
 */
internal fun interface PiBaselineProbe {
    suspend fun run(executable: Path, deadline: TimeMark): PiBaselineReceipt
}

internal class JvmPiBaselineProbe(private val dispatchers: DispatcherProvider) : PiBaselineProbe {
    private val log = Log.tag("PiBaselineProbe")

    override suspend fun run(executable: Path, deadline: TimeMark): PiBaselineReceipt {
        if (deadline.hasPassedNow()) {
            return PiBaselineReceipt(PiBaselineOutcome.Unobserved, PiBaselineOutcome.Unobserved)
        }
        return try {
            probe(executable, deadline)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "Pi admission baseline could not start" }
            PiBaselineReceipt(PiBaselineOutcome.Rejected, PiBaselineOutcome.Unobserved)
        } catch (e: SecurityException) {
            log.w(e.withoutDetails()) { "Pi admission baseline could not start" }
            PiBaselineReceipt(PiBaselineOutcome.Rejected, PiBaselineOutcome.Unobserved)
        }
    }

    private suspend fun probe(executable: Path, deadline: TimeMark): PiBaselineReceipt {
        val directory = withContext(dispatchers.io) { Files.createTempDirectory("pi-admission-") }
        val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        var process: Process? = null
        var connection: PiRpc? = null
        try {
            Files.writeString(directory.resolve("models.json"), piCatalogProbeJson())
            val builder = ProcessBuilder(probeCommand(executable)).directory(directory.toFile())
            val environment = builder.environment()
            retainPiEnvironment(environment)
            environment["PI_CODING_AGENT_DIR"] = directory.toString()
            environment["PI_OFFLINE"] = "1"
            environment["PI_SKIP_VERSION_CHECK"] = "1"
            // Not cancellable once started: the connection must exist so that `finally` stops the process.
            val started = withContext(NonCancellable) { builder.start() }
            process = started
            started.onExit().whenComplete { _, _ -> deleteTree(directory) }
            val remaining = (-deadline.elapsedNow()).inWholeMilliseconds.coerceIn(1L, COMMAND_TIMEOUT_MILLIS)
            val rpc = PiRpc(
                started,
                scope,
                dispatchers,
                {},
                { failure -> log.w(EngineException(failure)) { "Pi admission baseline failed" } },
                commandTimeoutMillis = remaining,
            )
            connection = rpc
            val catalog = try {
                val response = rpc.command(CATALOG_COMMAND)
                if (response["models"] is JsonArray) PiBaselineOutcome.Confirmed else PiBaselineOutcome.Rejected
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                log.w(e) { "Pi admission baseline command failed" }
                val isTimeout = e.failure == EngineFailure.Transport(TransportFailureReason.Timeout)
                if (isTimeout) PiBaselineOutcome.Unobserved else PiBaselineOutcome.Rejected
            } catch (e: IOException) {
                log.w(e.withoutDetails()) { "Pi admission baseline command failed" }
                PiBaselineOutcome.Rejected
            }
            return PiBaselineReceipt(PiBaselineOutcome.Confirmed, catalog)
        } finally {
            withContext(NonCancellable) {
                connection?.stopAndAwait() ?: process?.destroyForcibly()
                scope.cancel()
            }
        }
    }

    private fun deleteTree(directory: Path) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        } catch (e: IOException) {
            log.w(e) { "Pi admission baseline directory cleanup failed" }
        } catch (e: SecurityException) {
            log.w(e) { "Pi admission baseline directory cleanup was denied" }
        }
    }
}

private const val CATALOG_COMMAND = "get_available_models"
private const val COMMAND_TIMEOUT_MILLIS = 30_000L
