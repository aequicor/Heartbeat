package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Uses the actual PowerShell parser, passing the proposed command only as stdin data, never executable source. */
internal class PowerShellStructure(private val executable: Path, private val io: CoroutineDispatcher) {
    private val log = Log.tag("PowerShellStructure")

    suspend fun parse(command: String): ShellParseResult {
        if (command.isBlank() || command.length > MAX_COMMAND_CHARS) return unknown()
        return try {
            withTimeoutOrNull(PARSER_TIMEOUT_MILLIS) {
                withContext(io) { runParser(command) }
            } ?: unknown()
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
            "-Command",
            script,
        )
        val process = ProcessBuilder(shellProcessArguments(commandLine)).apply {
            val inherited = environment().filterKeys { it.uppercase(Locale.ROOT) in PARSER_ENVIRONMENT }
            environment().clear()
            environment().putAll(inherited)
            environment()["PSModulePath"] = executable.parent.resolve("Modules").toString()
            directory(executable.parent.toFile())
        }.redirectErrorStream(true).start()
        val output = async(io) { process.inputStream.readNBytes(MAX_OUTPUT_BYTES + 1) }
        val input = async(io) {
            process.outputStream.use { it.write(command.toByteArray(Charsets.UTF_8)) }
        }
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
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
