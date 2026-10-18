package io.aequicor.heartbeat.feature.harness.impl.data.github

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHttp
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubResponse
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds

/**
 * Direct gh api process, no shell. Authentication stays in gh's existing configuration. The bounded JSON input is
 * a private temporary file, never an argument or a log entry. stdout and stderr are separate, bounded and discarded
 * on timeout, cancellation or overflow. All child processes are killed before returning from a failed call.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopGitHubTransport(private val dispatchers: DispatcherProvider) : GitHubTransport {
    private val log = Log.tag("DesktopGitHubTransport")
    override val isAvailable = true

    @HighFrequency
    override suspend fun call(request: GitHubRequest): GitHubResponse = withContext(dispatchers.io) {
        log.v { "GitHub gh transport call" }
        try {
            execute(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = IllegalStateException("GitHub transport is unavailable")
            log.w(failure) { "GitHub transport failed (${error::class.simpleName.orEmpty()})" }
            GitHubResponse(0)
        }
    }

    private suspend fun execute(request: GitHubRequest): GitHubResponse {
        val directory = privateDirectory()
        return try {
            val process = start(request, directory)
            try {
                read(process)
            } finally {
                stop(process)
            }
        } finally {
            Files.deleteIfExists(directory.resolve("request.json"))
            Files.deleteIfExists(directory)
        }
    }

    private fun start(request: GitHubRequest, directory: Path): Process {
        val input = directory.resolve("request.json")
        request.body?.let { Files.writeString(input, it.toString()) }
        val arguments = mutableListOf(
            "gh", "api", "--hostname", "github.com", "--include", "--method", request.method,
            "-H", "Accept: application/vnd.github+json", "-H", "X-GitHub-Api-Version: 2026-03-10",
        )
        if (request.body != null) arguments += listOf("--input", input.toString())
        arguments += request.path
        return ProcessBuilder(arguments).directory(directory.toFile()).apply {
            environment().remove("GH_DEBUG")
            environment().remove("GH_FORCE_TTY")
            environment()["GH_PROMPT_DISABLED"] = "1"
            environment()["GH_PAGER"] = "cat"
            environment()["NO_COLOR"] = "1"
        }.start().also { it.outputStream.close() }
    }

    private suspend fun read(process: Process): GitHubResponse {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var discarded = 0
        val isCompleted = withTimeoutOrNull(REQUEST_SECONDS.seconds) {
            while (process.isAlive) {
                ensureActive()
                discarded = drain(process, output, buffer, discarded)
                delay(POLL_MILLIS)
            }
            drain(process, output, buffer, discarded)
            true
        } ?: false
        return if (isCompleted) decodeGitHubResponse(output.toString(Charsets.UTF_8)) else GitHubResponse(0)
    }

    private fun stop(child: Process) {
        child.toHandle().descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
        if (child.isAlive) child.destroyForcibly()
        child.inputStream.close()
        child.errorStream.close()
    }

    private fun privateDirectory(): Path = if (Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")))
            .supportsFileAttributeView("posix")
    ) {
        Files.createTempDirectory(
            "hb-github-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
    } else {
        Files.createTempDirectory("hb-github-")
    }

    private fun drain(process: Process, output: ByteArrayOutputStream, buffer: ByteArray, discardedBefore: Int): Int {
        while (process.inputStream.available() > 0) {
            val read = process.inputStream.read(buffer)
            if (read <= 0) break
            check(output.size() + read <= MAX_RESPONSE_BYTES) { "GitHub response limit" }
            output.write(buffer, 0, read)
        }
        // Error output can include private payload or auth details. Drain it without retaining it.
        var discarded = discardedBefore
        while (process.errorStream.available() > 0) {
            val read = process.errorStream.read(buffer)
            if (read <= 0) break
            discarded += read
            check(discarded <= MAX_RESPONSE_BYTES) { "GitHub diagnostic limit" }
        }
        return discarded
    }
}

/** Parses gh's --include output. Truncated, non-JSON and invalid status output are failures, never partial pages. */
internal fun decodeGitHubResponse(raw: String): GitHubResponse {
    val normalized = raw.replace("\r\n", "\n")
    val boundary = normalized.indexOf("\n\n")
    if (boundary < 0) return GitHubResponse(0)
    val headerLines = normalized.substring(0, boundary).lines()
    val status = headerLines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return GitHubResponse(0)
    if (status !in GitHubHttp.VALID) return GitHubResponse(0)
    val headers = headerLines.drop(1).mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon < 1) null else line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
    }.toMap()
    val body = normalized.substring(boundary + 2).trim()
    return GitHubResponse(status, if (body.isEmpty()) null else Json.parseToJsonElement(body), headers)
}

private const val MAX_RESPONSE_BYTES = 1024 * 1024

private const val BUFFER_BYTES = 8192
private const val REQUEST_SECONDS = 30
private const val POLL_MILLIS = 10L
