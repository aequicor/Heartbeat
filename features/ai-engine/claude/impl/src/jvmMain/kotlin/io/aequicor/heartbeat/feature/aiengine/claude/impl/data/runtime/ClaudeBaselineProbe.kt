package io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.CLAUDE_MODELS_INITIALIZE
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.claudeArguments
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.parseClaudeObject
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.readFrame
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.redacted
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.text
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.time.TimeMark

/** Outcome of one restricted baseline check: confirmed by observation, conclusively failed, or never observed. */
internal enum class ClaudeBaselineOutcome { Confirmed, Rejected, Unobserved }

/** What the isolated stream-json baseline observed; an unobserved check is never a pass. */
internal data class ClaudeBaselineReceipt(val initialize: ClaudeBaselineOutcome, val models: ClaudeBaselineOutcome) {
    val hasRejectedCheck: Boolean
        get() = initialize == ClaudeBaselineOutcome.Rejected || models == ClaudeBaselineOutcome.Rejected
}

/**
 * Isolated baseline of one exact Claude executable. It reuses the stream-json control handshake the adapter already
 * runs for model discovery: a bounded CLI process in a temporary home, config directory and working directory, with
 * a cleared environment and no login, sends only the initialize control request and never a model prompt. No
 * credentials, user home, project configuration, tools or history are read or written.
 */
internal fun interface ClaudeBaselineProbe {
    suspend fun run(executable: Path, deadline: TimeMark): ClaudeBaselineReceipt
}

internal class JvmClaudeBaselineProbe(private val dispatchers: DispatcherProvider) : ClaudeBaselineProbe {
    private val log = Log.tag("ClaudeBaselineProbe")

    override suspend fun run(executable: Path, deadline: TimeMark): ClaudeBaselineReceipt {
        if (deadline.hasPassedNow()) return UNOBSERVED
        return try {
            probe(executable, deadline)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.w(e.redacted()) { "Claude admission baseline could not start" }
            FAILED
        } catch (e: SecurityException) {
            log.w(e.redacted()) { "Claude admission baseline could not start" }
            FAILED
        }
    }

    private suspend fun probe(executable: Path, deadline: TimeMark): ClaudeBaselineReceipt {
        val directory = withContext(dispatchers.io) { Files.createTempDirectory("claude-admission-") }
        var process: Process? = null
        try {
            val home = withContext(dispatchers.io) { Files.createDirectory(directory.resolve("home")) }
            val config = withContext(dispatchers.io) { Files.createDirectory(directory.resolve("config")) }
            val work = withContext(dispatchers.io) { Files.createDirectory(directory.resolve("work")) }
            // Not cancellable once started: the process must exist so that `finally` stops and reaps it.
            val started = withContext(NonCancellable) { start(executable, work, home, config, directory) }
            process = started
            started.onExit().whenComplete { _, _ -> deleteTree(directory) }
            return exchange(started, deadline)
        } finally {
            withContext(NonCancellable) {
                process?.destroyTree()
                deleteTree(directory)
            }
        }
    }

    private fun start(executable: Path, work: Path, home: Path, config: Path, temp: Path): Process {
        val builder = ProcessBuilder(claudeBaselineArguments(executable))
            .directory(work.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val environment = builder.environment()
        val retained = environment.filterKeys { it.uppercase() in ClaudeProbeEnvironment }
        environment.clear()
        environment.putAll(retained)
        environment[HOME] = home.toString()
        environment[USER_PROFILE] = home.toString()
        environment[CONFIG_DIRECTORY] = config.toString()
        environment[TEMP] = temp.toString()
        environment[TMP] = temp.toString()
        environment[TMPDIR] = temp.toString()
        environment[DISABLE_AUTOUPDATER] = "1"
        environment[DISABLE_UPDATES] = "1"
        environment[TOOL_SEARCH] = "false"
        return builder.start()
    }

    private suspend fun exchange(process: Process, deadline: TimeMark): ClaudeBaselineReceipt {
        val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        val response = CompletableDeferred<ClaudeBaselineReceipt>()
        val reader = scope.launch { response.complete(readResponse(process)) }
        try {
            val remaining = remainingMillis(deadline) ?: return UNOBSERVED
            if (!writeInitialize(process)) return FAILED
            return withTimeoutOrNull(remaining) { response.await() } ?: UNOBSERVED
        } finally {
            withContext(NonCancellable) {
                // Killing the child closes the pipe and releases the blocking reader before it is joined.
                process.destroyTree()
                reader.join()
                scope.cancel()
            }
        }
    }

    private suspend fun writeInitialize(process: Process): Boolean = withContext(dispatchers.io) {
        try {
            val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
            writer.write(CLAUDE_MODELS_INITIALIZE)
            writer.newLine()
            writer.flush()
            true
        } catch (e: IOException) {
            log.w(e.redacted()) { "Claude admission baseline input failed" }
            false
        }
    }

    private suspend fun readResponse(process: Process): ClaudeBaselineReceipt = withContext(dispatchers.io) {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> readFrames(reader) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Claude admission baseline frame was rejected" }
            FAILED
        } catch (e: IOException) {
            log.w(e.redacted()) { "Claude admission baseline output ended" }
            FAILED
        }
    }

    private fun readFrames(reader: BufferedReader): ClaudeBaselineReceipt {
        while (true) {
            val line = reader.readFrame() ?: return FAILED
            if (line.isBlank()) continue
            response(parseClaudeObject(line))?.let { return it }
        }
    }

    private fun response(frame: JsonObject): ClaudeBaselineReceipt? {
        if (frame.text("type") != "control_response") return null
        val response = frame["response"] as? JsonObject ?: return FAILED
        if (response.text("request_id") != "models") return null
        if (response.text("subtype") != "success") return FAILED
        val body = response["response"] as? JsonObject ?: return PARTIAL
        return if (body["models"] is JsonArray) CONFIRMED else PARTIAL
    }

    private fun remainingMillis(deadline: TimeMark): Long? {
        if (deadline.hasPassedNow()) return null
        return (-deadline.elapsedNow()).inWholeMilliseconds.coerceIn(1L, COMMAND_TIMEOUT_MILLIS)
    }

    private fun Process.destroyTree() {
        descendants().forEach { it.destroyForcibly() }
        destroyForcibly()
        if (!waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.w { "Claude admission process exit timed out" }
        }
    }

    private fun deleteTree(directory: Path) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        } catch (e: IOException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Claude admission directory cleanup failed" }
        } catch (e: SecurityException) {
            log.w(IllegalStateException(e.javaClass.simpleName)) { "Claude admission directory cleanup was denied" }
        }
    }
}

/**
 * Exact launch of the restricted baseline: the production stream-json contract plus the `--input-format` the
 * discovery handshake already requires. It starts the same CLI kind as an ordinary turn, never a second process kind.
 */
internal fun claudeBaselineArguments(executable: Path): List<String> =
    listOf(executable.toString()) + claudeArguments() + listOf("--input-format", "stream-json")

private val CONFIRMED = ClaudeBaselineReceipt(ClaudeBaselineOutcome.Confirmed, ClaudeBaselineOutcome.Confirmed)
private val PARTIAL = ClaudeBaselineReceipt(ClaudeBaselineOutcome.Confirmed, ClaudeBaselineOutcome.Rejected)
private val FAILED = ClaudeBaselineReceipt(ClaudeBaselineOutcome.Rejected, ClaudeBaselineOutcome.Unobserved)
private val UNOBSERVED = ClaudeBaselineReceipt(ClaudeBaselineOutcome.Unobserved, ClaudeBaselineOutcome.Unobserved)

/** Only host variables the binary itself needs; credentials, proxies and user configuration are never retained. */
private val ClaudeProbeEnvironment = setOf("PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "LANG", "LC_ALL")

private const val HOME = "HOME"
private const val USER_PROFILE = "USERPROFILE"
private const val CONFIG_DIRECTORY = "CLAUDE_CONFIG_DIR"
private const val TEMP = "TEMP"
private const val TMP = "TMP"
private const val TMPDIR = "TMPDIR"
private const val DISABLE_AUTOUPDATER = "DISABLE_AUTOUPDATER"
private const val DISABLE_UPDATES = "DISABLE_UPDATES"
private const val TOOL_SEARCH = "ENABLE_TOOL_SEARCH"
private const val COMMAND_TIMEOUT_MILLIS = 30_000L
private const val EXIT_TIMEOUT_SECONDS = 5L
