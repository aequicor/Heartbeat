package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException

internal interface ClaudeTransport {
    suspend fun run(
        arguments: List<String>,
        input: String = "",
        workspace: WorkspaceRef? = null,
        closeInput: Boolean = true,
        line: suspend (String) -> Boolean,
    ): Int
}

/**
 * Native executable only, bounded UTF-8 frames, discarded stderr and an allowlisted host environment.
 * Every operation is its own process, so operations run concurrently; sessions serialize their own turns.
 */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class ProcessClaudeTransport(
    private val dispatchers: DispatcherProvider,
    private val configuration: ClaudeConfiguration = ClaudeConfiguration(),
) : ClaudeTransport {
    private val log = Log.tag("ClaudeProcess")

    override suspend fun run(
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        line: suspend (String) -> Boolean,
    ): Int = withContext(dispatchers.io) {
        log.d { "Starting Claude CLI operation" }
        try {
            execute(arguments, input, workspace, closeInput, line)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.w(e.redacted()) { "Claude process IO failed" }
            throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
        }
    }

    private suspend fun execute(
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        line: suspend (String) -> Boolean,
    ): Int = coroutineScope {
        val process = start(processBuilder(arguments, workspace))
        try {
            val writer = async {
                val stream = process.outputStream.bufferedWriter(Charsets.UTF_8)
                stream.write(input)
                stream.flush()
                if (closeInput) stream.close()
            }
            val reader = async {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { stream ->
                    var value = stream.readFrame()
                    while (value != null) {
                        if (value.isNotBlank() && line(value)) return@async 0
                        value = stream.readFrame()
                    }
                }
                process.waitFor()
            }
            val exit = reader.await()
            writer.await()
            log.d { "Claude CLI operation ended exit=$exit" }
            exit
        } finally {
            process.destroyForcibly()
        }
    }

    private fun start(builder: ProcessBuilder): Process = try {
        builder.start()
    } catch (e: IOException) {
        log.w(e.redacted()) { "Claude executable could not be started" }
        throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
    }

    private fun processBuilder(arguments: List<String>, workspace: WorkspaceRef?): ProcessBuilder {
        val executable = configuration.executable
        if (executable.endsWith(".cmd", true) || executable.endsWith(".bat", true)) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        val directory = if (workspace == null) {
            configuration.workingDirectory ?: System.getProperty("user.home")
        } else {
            configuration.workspaces[workspace] ?: throw EngineException(
                EngineFailure.Engine(EngineFailureReason.RequirementsNotMet),
            )
        }
        val builder = ProcessBuilder(listOf(executable) + arguments).directory(File(directory))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val environment = builder.environment()
        val allowed = claudeEnvironment(environment.toMap(), configuration)
        environment.clear()
        environment.putAll(allowed)
        return builder
    }
}

/**
 * Host variables passed to the CLI: no API keys or endpoint overrides. `CLAUDE_CONFIG_DIR` is set only when
 * configured, because an explicit value changes where the CLI looks up its default login.
 */
internal fun claudeEnvironment(host: Map<String, String>, configuration: ClaudeConfiguration): Map<String, String> =
    buildMap {
        putAll(host.filterKeys { it.uppercase() in HOST_ENVIRONMENT })
        configuration.configDirectory?.let { put("CLAUDE_CONFIG_DIR", it) }
    }

private fun BufferedReader.readFrame(): String? {
    val result = StringBuilder()
    var next = read()
    while (next != -1 && next != '\n'.code) {
        if (result.length >= MAX_FRAME_CHARS) protocolFailure()
        result.append(next.toChar())
        next = read()
    }
    return if (next == -1 && result.isEmpty()) null else result.toString().trimEnd('\r')
}

private const val MAX_FRAME_CHARS = 2 * 1024 * 1024
private val HOST_ENVIRONMENT = setOf(
    "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
    "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "APPDATA", "LOCALAPPDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
    "USER", "LOGNAME", "USERNAME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE",
)
