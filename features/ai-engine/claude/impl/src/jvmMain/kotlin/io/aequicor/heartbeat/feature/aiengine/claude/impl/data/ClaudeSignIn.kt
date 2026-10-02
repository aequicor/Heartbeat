package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.hostOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.Writer

/** Starts a CLI process with stdout and stderr merged; tests replace it with a fake CLI. */
internal fun interface ClaudeProcessStarter {
    fun start(command: List<String>, directory: File, environment: Map<String, String>): Process

    companion object {
        val Default = ClaudeProcessStarter { command, directory, environment ->
            val builder = ProcessBuilder(command).directory(directory).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(environment)
            builder.start()
        }
    }
}

/**
 * Claude Code CLI sign-in with a given launch context, so the account lands in the config directory its runtimes
 * use. `claude auth login` opens the browser and waits for the callback itself; Heartbeat shows the sign-in page
 * (https on Anthropic's hosts) and forwards a code the page shows when the callback cannot reach the CLI. A CLI that
 * signs in only from a terminal is answered with the command to run there. URLs, codes and paths are never logged.
 */
@Inject
internal class ClaudeSignIn(
    private val dispatchers: DispatcherProvider,
    private val configuration: ClaudeConfiguration = ClaudeConfiguration(),
    private val starter: ClaudeProcessStarter = ClaudeProcessStarter.Default,
    private val isWindows: Boolean = ClaudeHost().isWindows,
) {
    private val log = Log.tag("ClaudeSignIn")

    /** The CLI login: signed in only with a claude.ai subscription, which is the one Heartbeat can use. */
    suspend fun status(launch: LaunchContext): LoginState {
        val (exit, output) = command(startup(launch), listOf("auth", "status"))
        if (exit !in 0..1) protocolFailure()
        // Warnings the CLI prints around the JSON object are ignored.
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end < start) protocolFailure()
        val status = parseClaudeObject(output.substring(start, end + 1))
        val isLoggedIn = status.text("loggedIn") == "true"
        log.i { "Claude CLI login checked loggedIn=$isLoggedIn" }
        return if (isLoggedIn && status.text("authMethod") == "claude.ai") {
            LoginState.SignedIn(status.text("email"))
        } else {
            LoginState.SignedOut
        }
    }

    suspend fun login(launch: LaunchContext, session: LoginSession): LoginState {
        val startup = startup(launch)
        log.i { "Claude sign-in started" }
        val exit = withContext(dispatchers.io) { converse(startup, session) }
        val state = if (exit == 0) status(launch) else LoginState.SignedOut
        if (state !is LoginState.SignedIn) {
            log.w { "Claude sign-in rejected exit=$exit" }
            throw ManagementException(ManagementFailure.Login(LoginFailureReason.Rejected))
        }
        log.i { "Claude sign-in completed" }
        return state
    }

    suspend fun logout(launch: LaunchContext): LoginState {
        val (exit, _) = command(startup(launch), listOf("auth", "logout"))
        val state = if (exit == 0) status(launch) else LoginState.Unknown
        if (state != LoginState.SignedOut) {
            log.w { "Claude sign-out rejected exit=$exit" }
            throw ManagementException(ManagementFailure.Login(LoginFailureReason.Rejected))
        }
        log.i { "Claude CLI signed out" }
        return state
    }

    private fun startup(launch: LaunchContext): ClaudeStartup {
        val startup = resolveClaudeStartup(launch, configuration)
        if (!startup.isRunnable) {
            log.w { "Claude executable is missing or not runnable source=${startup.source}" }
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        return startup
    }

    /** Runs `auth login` until it exits; prompts the user as the CLI asks, answers pasted codes on stdin. */
    private suspend fun converse(startup: ClaudeStartup, session: LoginSession): Int = coroutineScope {
        val process = start(startup, listOf("auth", "login"))
        val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
        try {
            val lines = output(process)
            val url = withTimeoutOrNull(URL_TIMEOUT_MILLIS) { signInUrl(lines) } ?: run {
                log.w { "Claude CLI offered no sign-in page; it needs a terminal" }
                throw ManagementException(
                    ManagementFailure.Login(
                        LoginFailureReason.RequiresTerminal,
                        claudeTerminalCommand(startup, isWindows),
                    ),
                )
            }
            session.prompt(LoginPrompt.OpenUrl(url))
            var code: Job? = null
            for (line in lines) {
                if (code == null && PastePrompt.containsMatchIn(line)) {
                    session.prompt(LoginPrompt.PasteCode(url))
                    code = launch { answer(input, session.awaitCode().value.trim()) }
                } else if (Success.containsMatchIn(line)) {
                    // The CLI may wait for Enter before it exits.
                    answer(input, "")
                }
            }
            code?.cancel()
            runInterruptible { process.waitFor() }.also { log.i { "Claude sign-in process ended exit=$it" } }
        } finally {
            process.destroyTree()
            close(input)
        }
    }

    /** The first OAuth page the CLI prints, or null when it exits without one. */
    private suspend fun signInUrl(lines: ReceiveChannel<String>): String? {
        for (line in lines) {
            val url = UrlPattern.findAll(line).map { it.value.trimEnd('.', ',', ')') }.firstOrNull { "/oauth/" in it }
                ?: continue
            if (!isAnthropicPage(url)) {
                log.w { "Claude CLI offered a sign-in page outside Anthropic's hosts" }
                throw ManagementException(ManagementFailure.Login(LoginFailureReason.UntrustedUrl))
            }
            return url
        }
        return null
    }

    /** Output split into lines; a prompt still waiting for input on its line is passed on as soon as it appears. */
    private fun CoroutineScope.output(process: Process): ReceiveChannel<String> = produce(
        capacity = Channel.UNLIMITED,
    ) {
        val pending = StringBuilder()
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(BUFFER_CHARS)
                var count = reader.read(buffer)
                while (count >= 0) {
                    pending.append(buffer, 0, count)
                    lines(pending).forEach { send(it) }
                    count = reader.read(buffer)
                }
            }
        } catch (e: IOException) {
            // A stopped sign-in closes the stream under the reader.
            log.w(e.redacted()) { "Claude sign-in output ended" }
        }
        if (pending.isNotEmpty()) send(clean(pending.toString()))
    }

    /** Complete lines taken from [pending], plus a prompt that waits for input at the end of it. */
    private fun lines(pending: StringBuilder): List<String> = buildList {
        var end = pending.indexOf("\n")
        while (end >= 0) {
            add(clean(pending.substring(0, end)))
            pending.delete(0, end + 1)
            end = pending.indexOf("\n")
        }
        if (pending.length > MAX_LINE_CHARS || PastePrompt.containsMatchIn(pending)) {
            add(clean(pending.toString()))
            pending.clear()
        }
    }

    /** One bounded CLI command without input: its exit code and output. */
    private suspend fun command(startup: ClaudeStartup, arguments: List<String>): Pair<Int, String> =
        withContext(dispatchers.io) {
            coroutineScope {
                val process = start(startup, arguments)
                try {
                    process.outputStream.close()
                    val output = async { read(process) }
                    withProbeTimeout { output.await() to runInterruptible { process.waitFor() } }
                        .let { (text, exit) -> exit to text }
                } finally {
                    process.destroyTree()
                }
            }
        }

    private fun read(process: Process): String = try {
        process.inputStream.readNBytes(MAX_OUTPUT_BYTES).decodeToString()
    } catch (e: IOException) {
        log.w(e.redacted()) { "Claude CLI output ended" }
        ""
    }

    private fun start(startup: ClaudeStartup, arguments: List<String>): Process = try {
        val directory = File(configuration.workingDirectory ?: System.getProperty("user.home"))
        starter.start(listOf(startup.executable) + arguments, directory, claudeEnvironment(System.getenv(), startup))
    } catch (e: IOException) {
        log.w(e.redacted()) { "Claude executable could not be started" }
        throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
    }

    private fun answer(input: Writer, text: String) {
        try {
            input.write(text + "\n")
            input.flush()
        } catch (e: IOException) {
            log.w(e.redacted()) { "Claude sign-in stopped reading input" }
        }
    }

    private fun close(input: Writer) {
        try {
            input.close()
        } catch (e: IOException) {
            log.w(e.redacted()) { "Claude sign-in input could not be closed" }
        }
    }

    private fun Process.destroyTree() {
        descendants().forEach { it.destroyForcibly() }
        destroyForcibly()
    }

    private companion object {
        const val URL_TIMEOUT_MILLIS = 20_000L
        const val BUFFER_CHARS = 4096
        const val MAX_LINE_CHARS = 16 * 1024
        const val MAX_OUTPUT_BYTES = 64 * 1024
        val AnthropicHosts = listOf("claude.ai", "claude.com", "anthropic.com")
        val UrlPattern = Regex("""https://[^\s"'<>\u0007\u001B]+""")
        val PastePrompt = Regex("paste.{0,20}code", RegexOption.IGNORE_CASE)
        val Success = Regex("login successful", RegexOption.IGNORE_CASE)
        val Escape = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

        fun clean(line: String): String = Escape.replace(line, "").trimEnd('\r')

        fun isAnthropicPage(url: String): Boolean =
            hostOf(url).let { host -> AnthropicHosts.any { host == it || host.endsWith(".$it") } }
    }
}

/**
 * The command that signs in from a terminal with the same executable and config directory: POSIX shell quoting,
 * or PowerShell on Windows.
 */
internal fun claudeTerminalCommand(startup: ClaudeStartup, isWindows: Boolean): String {
    val quote: (String) -> String = if (isWindows) {
        { "'" + it.replace("'", "''") + "'" }
    } else {
        { "'" + it.replace("'", "'\\''") + "'" }
    }
    val directory = startup.configDirectory
    return when {
        isWindows && directory != null -> "\$env:CLAUDE_CONFIG_DIR=${quote(
            directory,
        )}; & ${quote(startup.executable)} auth login"

        isWindows -> "& ${quote(startup.executable)} auth login"

        directory != null -> "CLAUDE_CONFIG_DIR=${quote(directory)} ${quote(startup.executable)} auth login"

        else -> "${quote(startup.executable)} auth login"
    }
}
