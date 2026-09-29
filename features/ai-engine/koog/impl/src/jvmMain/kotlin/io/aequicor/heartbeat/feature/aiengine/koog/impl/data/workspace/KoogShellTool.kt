package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.workspace

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogTool
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogToolResult
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.argInt
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.argText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs one shell command in the project root: PowerShell on Windows, `/bin/sh` elsewhere. Always mutating: a
 * command can do anything the user can. Secrets-looking environment variables are not inherited, stdin is closed,
 * the process tree is killed on timeout or turn cancellation, and output is truncated for the model. Children
 * detached from the shell (build daemons, `cmd &`) outlive the command; their output is read only briefly after exit.
 */
internal class KoogShellTool(
    private val root: ProjectRoot,
    private val io: CoroutineDispatcher,
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
) : KoogTool {
    private val log = Log.tag("KoogShellTool")

    override val descriptor = ToolDescriptor(
        "run_command",
        "Run a ${if (isWindows) "PowerShell" else "POSIX shell"} command in the project root and return its exit " +
            "code and combined output. Use for builds, tests, git and other CLI tools; not for interactive programs.",
        listOf(ToolParameterDescriptor("command", "Command line to run", ToolParameterType.String)),
        listOf(
            ToolParameterDescriptor(
                "timeout_seconds",
                "Time limit, default $DEFAULT_TIMEOUT_SECONDS, at most $MAX_TIMEOUT_SECONDS",
                ToolParameterType.Integer,
            ),
        ),
    )
    override val isMutating = true

    override fun target(args: JsonObject) = args.argText("command")

    override suspend fun run(args: JsonObject): KoogToolResult {
        val command = args.argText("command")
        if (command.isBlank()) return KoogToolResult("command is empty", true)
        val timeout = (args.argInt("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS).coerceIn(1, MAX_TIMEOUT_SECONDS)
        return try {
            execute(command, timeout.toLong())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.w(e) { "Command could not start" }
            KoogToolResult("Could not start the command: ${e.message.orEmpty()}", true)
        }
    }

    private suspend fun execute(command: String, timeout: Long): KoogToolResult = withContext(io) {
        val shell = if (isWindows) {
            listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command)
        } else {
            listOf("/bin/sh", "-c", command)
        }
        val builder = ProcessBuilder(shell)
            .directory(root.path.toFile())
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null")))
        val removed = builder.environment().keys.filter(::isSecretName)
        removed.forEach { builder.environment().remove(it) }
        log.i { "Starting command; ${removed.size} secret-like variables withheld" }
        val process = builder.start()
        val output = OutputCollector()
        val isFinished = try {
            output.drain(process, TimeUnit.SECONDS.toMillis(timeout))
        } finally {
            if (process.isAlive) kill(process)
            closeOutput(process)
        }
        if (isFinished) {
            val code = process.exitValue()
            log.i { "Command exited with $code" }
            KoogToolResult("Exit code: $code\n${output.text()}", code != 0)
        } else {
            log.w { "Command timed out after ${timeout}s" }
            KoogToolResult("Timed out after ${timeout}s\n${output.text()}", true)
        }
    }

    private fun kill(process: Process) {
        log.i { "Killing command process tree" }
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    private fun closeOutput(process: Process) {
        try {
            process.inputStream.close()
        } catch (e: IOException) {
            log.w(e) { "Command output could not be closed" }
        }
    }

    /**
     * Keeps the head and the tail of the output, which is where commands print what matters. Output is polled
     * instead of read to EOF: a background child that inherited stdout (a build daemon, `cmd &`) would otherwise
     * keep the pipe open forever, and a blocking read ignores cancellation.
     */
    private inner class OutputCollector {
        private val head = ByteArrayOutputStream()
        private val tail = ByteArray(TAIL_BYTES)
        private var tailStart = 0
        private var tailSize = 0
        private var dropped = 0L

        /** Collects output until the process exits or [timeoutMillis] passes; returns whether it exited. */
        suspend fun drain(process: Process, timeoutMillis: Long): Boolean {
            val input = process.inputStream
            val buffer = ByteArray(BUFFER_BYTES)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var exitedAt = 0L
            var hasExited: Boolean? = null
            while (hasExited == null) {
                val read = readAvailable(input, buffer)
                val now = System.nanoTime()
                val isAlive = process.isAlive
                if (exitedAt == 0L && !isAlive) exitedAt = now
                hasExited = drainOutcome(isAlive, read, now - exitedAt, now > deadline)
                if (hasExited == null) {
                    if (read == 0) delay(POLL_MILLIS) else yield()
                }
            }
            return hasExited
        }

        /** Reads what is available without blocking: the byte count, 0 when nothing is ready, -1 at end of output. */
        private fun readAvailable(input: InputStream, buffer: ByteArray): Int {
            val available = input.available()
            if (available <= 0) return 0
            val read = input.read(buffer, 0, minOf(available, buffer.size))
            if (read > 0) append(buffer, read)
            return read
        }

        private fun append(bytes: ByteArray, count: Int) {
            val toHead = minOf(count, HEAD_BYTES - head.size())
            if (toHead > 0) head.write(bytes, 0, toHead)
            for (i in toHead until count) {
                if (tailSize < TAIL_BYTES) {
                    tail[(tailStart + tailSize) % TAIL_BYTES] = bytes[i]
                    tailSize++
                } else {
                    tail[tailStart] = bytes[i]
                    tailStart = (tailStart + 1) % TAIL_BYTES
                    dropped++
                }
            }
        }

        /** Collected output; read only after [drain] returned. */
        fun text(): String {
            val tailBytes = ByteArray(tailSize) { tail[(tailStart + it) % TAIL_BYTES] }
            return buildString {
                append(head.toString(Charsets.UTF_8))
                if (dropped > 0) append("\n… $dropped bytes omitted …\n")
                append(tailBytes.toString(Charsets.UTF_8))
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val MAX_TIMEOUT_SECONDS = 600
        const val HEAD_BYTES = 8_000
        const val TAIL_BYTES = 16_000
        const val BUFFER_BYTES = 4096
        const val POLL_MILLIS = 20L
        val SECRET_PARTS = listOf("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH")

        fun isSecretName(name: String): Boolean = name.uppercase().let { upper -> SECRET_PARTS.any { it in upper } }
    }
}

private const val EXIT_GRACE_MILLIS = 200L
private const val AFTER_EXIT_LIMIT_MILLIS = 2_000L

/**
 * Whether draining a command's output is over: true at end of output or once the process exited and its pipe stayed
 * quiet for a moment, false on timeout, null to keep reading. A detached child may hold the pipe and keep writing
 * after the shell exited, so output after exit is collected for a bounded time only.
 *
 * @param isAlive whether the command process still runs.
 * @param read bytes read by the last poll, 0 when nothing was ready, -1 at end of output.
 * @param sinceExitNanos time since the process was first seen exited; meaningless while it is alive.
 * @param isPastDeadline whether the command's time limit has passed.
 */
internal fun drainOutcome(isAlive: Boolean, read: Int, sinceExitNanos: Long, isPastDeadline: Boolean): Boolean? = when {
    read < 0 -> true
    isAlive -> if (isPastDeadline) false else null
    sinceExitNanos > TimeUnit.MILLISECONDS.toNanos(AFTER_EXIT_LIMIT_MILLIS) -> true
    read == 0 && sinceExitNanos > TimeUnit.MILLISECONDS.toNanos(EXIT_GRACE_MILLIS) -> true
    else -> null
}
