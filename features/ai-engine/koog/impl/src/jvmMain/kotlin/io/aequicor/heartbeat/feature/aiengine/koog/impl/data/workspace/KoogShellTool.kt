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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs one shell command in the project root: PowerShell on Windows, `/bin/sh` elsewhere. Always mutating: a
 * command can do anything the user can. Secrets-looking environment variables are not inherited, stdin is closed,
 * the process tree is killed on timeout or turn cancellation, and output is truncated for the model.
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

    private suspend fun execute(command: String, timeout: Long): KoogToolResult {
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
        return coroutineScope {
            // Killing the process closes its output, which ends the blocking drain.
            val drain = launch(io) { output.drain(process) }
            val isFinished = try {
                runInterruptible(io) { process.waitFor(timeout, TimeUnit.SECONDS) }
            } finally {
                if (process.isAlive) kill(process)
            }
            drain.join()
            if (isFinished) {
                val code = process.exitValue()
                log.i { "Command exited with $code" }
                KoogToolResult("Exit code: $code\n${output.text()}", code != 0)
            } else {
                log.w { "Command timed out after ${timeout}s" }
                KoogToolResult("Timed out after ${timeout}s\n${output.text()}", true)
            }
        }
    }

    private fun kill(process: Process) {
        log.i { "Killing command process tree" }
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** Keeps the head and the tail of the output, which is where commands print what matters. */
    private inner class OutputCollector {
        private val head = StringBuilder()
        private val tail = ArrayDeque<Char>()
        private var dropped = 0L

        fun drain(process: Process) {
            try {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(BUFFER_CHARS)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        repeat(read) { append(buffer[it]) }
                    }
                }
            } catch (e: IOException) {
                log.w(e) { "Command output closed early" }
            }
        }

        private fun append(char: Char) {
            if (head.length < HEAD_CHARS) {
                head.append(char)
                return
            }
            tail.addLast(char)
            if (tail.size > TAIL_CHARS) {
                tail.removeFirst()
                dropped++
            }
        }

        /** Collected output; read only after [drain] returned. */
        fun text(): String = buildString {
            append(head)
            if (dropped > 0) append("\n… $dropped characters omitted …\n")
            tail.forEach(::append)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val MAX_TIMEOUT_SECONDS = 600
        const val HEAD_CHARS = 8_000
        const val TAIL_CHARS = 16_000
        const val BUFFER_CHARS = 4096
        val SECRET_PARTS = listOf("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH")

        fun isSecretName(name: String): Boolean = name.uppercase().let { upper -> SECRET_PARTS.any { it in upper } }
    }
}
