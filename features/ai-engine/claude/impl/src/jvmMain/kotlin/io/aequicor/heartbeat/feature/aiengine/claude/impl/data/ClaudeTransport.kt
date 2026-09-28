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
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridge
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Runs one CLI operation. [line] returning `true` stops reading and kills the child; `run` then returns 0.
 * With `closeInput = false` stdin stays open until the operation ends. An input that was not fully written
 * fails the operation with `Unavailable`, even after an early stop. `RequirementsNotMet` is reported only
 * before a child process exists, so the input was certainly not delivered.
 */
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
    private val searchBridge: SearchBridge,
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
            // Killing the child on cancellation or timeout breaks its pipes; that is not an IO failure.
            ensureActive()
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
    ): Int {
        val bridgeConfig = if (SEARCH_BRIDGE_MARKER in arguments) searchConfig() else null
        val effectiveArguments = bridgeConfig?.let { claudeSearchArguments(arguments, it) } ?: arguments
        try {
            val process = start(processBuilder(effectiveArguments, workspace))
            try {
                return communicate(process, input, closeInput, line)
            } finally {
                process.destroyTree()
                closeInput(process)
            }
        } finally {
            bridgeConfig?.let(Files::deleteIfExists)
        }
    }

    /** Bridge start and config file failures surface as an unavailable engine, never as a raw exception. */
    private val configDirectory by lazy { mcpConfigDirectory() }

    private fun searchConfig(): Path = try {
        claudeSearchConfig(searchBridge.endpoint(), configDirectory)
    } catch (e: IOException) {
        log.w(e) { "Search bridge config could not be prepared" }
        throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
    } catch (e: IllegalStateException) {
        log.w(e) { "Search bridge is closed" }
        throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
    }

    /** One owner-only directory; files left by a crashed run are removed once per profile transport. */
    private fun mcpConfigDirectory(): Path {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), MCP_CONFIG_DIRECTORY)
        Files.createDirectories(directory)
        restrictToOwner(directory, directory = true)
        val staleBefore = System.currentTimeMillis() - STALE_CONFIG_MILLIS
        Files.list(directory).use { files ->
            files.filter { Files.getLastModifiedTime(it).toMillis() < staleBefore }.forEach { stale ->
                try {
                    Files.deleteIfExists(stale)
                } catch (e: IOException) {
                    log.w(e) { "Stale MCP config could not be removed" }
                }
            }
        }
        log.d { "MCP config directory ready" }
        return directory
    }

    private suspend fun communicate(
        process: Process,
        input: String,
        closeInput: Boolean,
        line: suspend (String) -> Boolean,
    ): Int = coroutineScope {
        val writer = async { writeInput(process, input, closeInput) }
        val reader = async {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { stream ->
                var value = stream.readFrame()
                while (value != null) {
                    ensureActive()
                    if (value.isNotBlank() && line(value)) return@async 0
                    value = stream.readFrame()
                }
            }
            process.waitFor()
        }
        try {
            val exit = reader.await()
            process.destroyTree()
            closeInput(process)
            val writeFailure = writer.await()
            if (writeFailure != null && input.isNotEmpty()) {
                throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
            }
            log.d { "Claude CLI operation ended exit=$exit" }
            exit
        } finally {
            process.destroyTree()
            closeInput(process)
        }
    }

    /** Returns the write failure instead of throwing, so the reader keeps the child's frames and exit code. */
    private fun writeInput(process: Process, input: String, closeInput: Boolean): IOException? {
        val stream = process.outputStream.bufferedWriter(Charsets.UTF_8)
        return try {
            stream.write(input)
            stream.flush()
            if (closeInput) stream.close()
            null
        } catch (e: IOException) {
            log.w(e.redacted()) { "Claude process stopped reading input" }
            e
        }
    }

    /**
     * Descendants are captured while the CLI is alive: a helper that inherited stdout would otherwise keep the
     * pipe open, and the reader would never see EOF after cancellation.
     */
    private fun Process.destroyTree() {
        descendants().forEach { it.destroyForcibly() }
        destroyForcibly()
    }

    /** Windows does not close the parent's pipe handles when the child is destroyed. */
    private fun closeInput(process: Process) {
        try {
            process.outputStream.close()
        } catch (e: IOException) {
            log.w(e.redacted()) { "Claude process input could not be closed" }
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

internal fun claudeSearchArguments(arguments: List<String>, config: Path): List<String> =
    arguments.filterNot { it == SEARCH_BRIDGE_MARKER || it == "--tools=" } + listOf(
        "--tools=mcp__heartbeat_search__web_search,mcp__heartbeat_search__web_fetch",
        "--allowedTools=mcp__heartbeat_search__web_search,mcp__heartbeat_search__web_fetch",
        "--mcp-config",
        config.toString(),
    )

internal fun claudeSearchConfig(endpoint: SearchBridgeEndpoint, directory: Path): Path {
    val config = buildJsonObject {
        put(
            "mcpServers",
            buildJsonObject {
                put(
                    "heartbeat_search",
                    buildJsonObject {
                        put("type", "http")
                        put("url", "${endpoint.origin}/mcp")
                        put("headers", buildJsonObject { put("Authorization", "Bearer ${endpoint.token}") })
                    },
                )
            },
        )
    }
    // The file carries the bridge bearer: owner-only before any content is written; deleted after the run.
    val file = Files.createTempFile(directory, "heartbeat-mcp-", ".json")
    restrictToOwner(file, directory = false)
    Files.writeString(file, config.toString())
    return file
}

/**
 * Restricts [path] to its owner: POSIX permissions, otherwise a single owner-only ACL entry (Windows).
 * On a file system with neither view the platform temp-directory permissions are the only protection.
 */
internal fun restrictToOwner(path: Path, directory: Boolean) {
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
    val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
    when {
        posix != null -> posix.setPermissions(
            PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"),
        )

        acl != null -> acl.acl = listOf(
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.owner)
                .setPermissions(AclEntryPermission.entries.toSet())
                .build(),
        )
    }
}

private const val MCP_CONFIG_DIRECTORY = "heartbeat-mcp"
private const val STALE_CONFIG_MILLIS = 24L * 60 * 60 * 1000

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
