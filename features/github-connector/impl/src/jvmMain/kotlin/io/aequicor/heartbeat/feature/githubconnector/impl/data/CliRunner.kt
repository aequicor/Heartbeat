package io.aequicor.heartbeat.feature.githubconnector.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubCliException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Exit code and decoded output of a finished CLI call. */
internal class CliResult(val exitCode: Int, val output: String) {
    override fun toString(): String = "CliResult(exitCode=$exitCode)"
}

/** Runs the GitHub CLI without a shell; implementations never log output, which may hold a token. */
internal fun interface CliRunner {
    /** Runs [command]; [mergeError] appends stderr to the output, otherwise stderr is discarded. */
    suspend fun run(command: List<String>, environment: Map<String, String>, mergeError: Boolean): CliResult
}

/** Bounded process runner: fixed argument list, tmp working directory, size and time limits. */
internal class ProcessCliRunner(
    private val dispatchers: DispatcherProvider,
    private val timeout: Duration = TIMEOUT_SECONDS.seconds,
) : CliRunner {
    private val log = Log.tag("ProcessCliRunner")

    override suspend fun run(command: List<String>, environment: Map<String, String>, mergeError: Boolean): CliResult =
        withContext(dispatchers.io) {
            val builder = ProcessBuilder(command).directory(File(System.getProperty("java.io.tmpdir")))
            if (mergeError) {
                builder.redirectErrorStream(true)
            } else {
                builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            }
            builder.environment().apply {
                sanitizeCliEnvironment(this)
                putAll(environment)
            }
            val process = builder.start()
            try {
                process.outputStream.close()
                // A hanging gh (network stall, prompt) is a failed step, not a cancelled one: the screen must leave
                // the loading state. Only this call's own timeout is turned into a failure.
                collect(process) ?: run {
                    log.w { "GitHub CLI call timed out" }
                    throw GitHubCliException(GitHubConnectorFailure.Failed)
                }
            } finally {
                process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
            }
        }

    private suspend fun collect(process: Process): CliResult? = withTimeoutOrNull(timeout) {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (process.isAlive) {
            ensureActive()
            drain(process.inputStream, output, buffer)
            delay(POLL_MILLIS)
        }
        drain(process.inputStream, output, buffer)
        CliResult(process.exitValue(), output.toString(Charsets.UTF_8))
    }

    private fun drain(input: java.io.InputStream, output: ByteArrayOutputStream, buffer: ByteArray) {
        while (input.available() > 0) {
            val count = input.read(buffer)
            if (count <= 0) break
            check(output.size() + count <= MAX_OUTPUT_BYTES) { "CLI output exceeded the limit" }
            output.write(buffer, 0, count)
        }
    }
}

/** Inherited variables cannot select another host or account, enable debug output or prompt the user. */
internal fun sanitizeCliEnvironment(environment: MutableMap<String, String>) {
    environment.keys.filter { it.uppercase() in DROPPED_VARIABLES }.forEach(environment::remove)
    environment["GH_PROMPT_DISABLED"] = "1"
    environment["GH_PAGER"] = "cat"
    environment["NO_COLOR"] = "1"
}

private val DROPPED_VARIABLES = setOf(
    "GH_TOKEN",
    "GITHUB_TOKEN",
    "GH_ENTERPRISE_TOKEN",
    "GITHUB_ENTERPRISE_TOKEN",
    "GH_DEBUG",
    "GH_HOST",
)
private const val TIMEOUT_SECONDS = 20
private const val POLL_MILLIS = 10L
private const val BUFFER_BYTES = 8192
private const val MAX_OUTPUT_BYTES = 262_144
