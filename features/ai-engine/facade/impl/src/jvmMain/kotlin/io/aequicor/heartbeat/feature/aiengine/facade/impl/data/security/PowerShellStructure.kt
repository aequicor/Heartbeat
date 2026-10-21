package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Uses the actual PowerShell parser, passing the proposed command only as stdin data, never executable source.
 * The bounded budget includes cold Windows PowerShell startup, not just AST parsing. Exhaustion stays Unknown.
 */
internal class PowerShellStructure(
    private val executable: Path,
    private val io: CoroutineDispatcher,
    private val timeoutMillis: Long = PARSER_TIMEOUT_MILLIS,
    private val onStage: (PowerShellParserStage) -> Unit = {},
) {
    private val log = Log.tag("PowerShellStructure")

    suspend fun parse(command: String): ShellParseResult {
        if (command.isBlank() || command.length > MAX_COMMAND_CHARS) return unknown()
        return try {
            withTimeoutOrNull(timeoutMillis) {
                withContext(io) { runParser(command) }
            } ?: ShellParseResult.Unknown("PowerShell parser exceeded its startup and analysis budget").also {
                log.w { "PowerShell structural parser timed out" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "PowerShell structural analysis unavailable" }
            unknown()
        }
    }

    private suspend fun runParser(command: String): ShellParseResult = coroutineScope {
        val script = checkNotNull(javaClass.getResourceAsStream("/security/powershell-structure.ps1")) {
            "PowerShell parser resource unavailable"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val commandLine = listOf(
            executable.toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            // The trusted program crosses argv as ASCII data, avoiding Windows PowerShell's command-string
            // quoting rules and ProcessBuilder's legacy handling of embedded quotes. Model input stays stdin.
            "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
        )
        val process = ProcessBuilder(commandLine).apply {
            val inherited = environment().filterKeys { it.uppercase(Locale.ROOT) in PARSER_ENVIRONMENT }
            environment().clear()
            environment().putAll(inherited)
            environment()["PSModulePath"] = executable.parent.resolve("Modules").toString()
            directory(executable.parent.toFile())
        }.redirectErrorStream(true).start()
        val output = async(io, start = CoroutineStart.LAZY) {
            process.inputStream.readNBytes(MAX_OUTPUT_BYTES + 1).also {
                onStage(PowerShellParserStage.StdoutRead)
            }
        }
        val input = async(io, start = CoroutineStart.LAZY) {
            process.outputStream.use { it.write("${command.length}\n$command".toByteArray(Charsets.UTF_8)) }
            onStage(PowerShellParserStage.StdinClosed)
        }
        try {
            onStage(PowerShellParserStage.Started)
            output.start()
            input.start()
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
            onStage(PowerShellParserStage.Exited)
            input.await()
            val bytes = output.await()
            if (process.exitValue() != 0 || bytes.size > MAX_OUTPUT_BYTES) {
                unknown()
            } else {
                decode(bytes.toString(Charsets.UTF_8))
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.outputStream.close()
            process.inputStream.close()
            input.cancel()
            output.cancel()
        }
    }

    private fun decode(text: String): ShellParseResult {
        val document = Json.decodeFromString<PowerShellDocument>(text)
        require(document.statements.isNotEmpty()) { "PowerShell parse has no statements" }
        return ShellParseResult.Parsed(document.statements.map { it.pipeline() })
    }
}

/** Constant transport checkpoints for a host-owned diagnostic observer; never includes commands or process output. */
internal enum class PowerShellParserStage { Started, StdinClosed, Exited, StdoutRead }

@Serializable
private data class PowerShellDocument(val statements: List<PowerShellPipeline>)

@Serializable
private data class PowerShellPipeline(val commands: List<PowerShellCommand>) {
    fun pipeline(): ShellPipeline {
        require(commands.isNotEmpty()) { "PowerShell pipeline has no commands" }
        return ShellPipeline(commands.map { it.command() })
    }
}

@Serializable
private data class PowerShellCommand(val words: List<String>, val redirects: List<PowerShellRedirection>) {
    fun command(): ShellCommand {
        require(words.isNotEmpty()) { "PowerShell command has no words" }
        return ShellCommand(words, redirects.map { it.redirection() })
    }
}

@Serializable
private data class PowerShellRedirection(val target: String, val kind: String, val fileDescriptor: Int) {
    fun redirection() = ShellRedirection(target, ShellRedirectionKind.valueOf(kind), fileDescriptor)
}

private fun unknown(): ShellParseResult = ShellParseResult.Unknown("PowerShell syntax has no complete literal analysis")
private const val MAX_COMMAND_CHARS = 32_768
private const val MAX_OUTPUT_BYTES = 256 * 1024
private const val PARSER_TIMEOUT_MILLIS = 5_000L
private const val POLL_MILLIS = 50L
private val PARSER_ENVIRONMENT = setOf("SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "TEMP", "TMP", "TMPDIR")
