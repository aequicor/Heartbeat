package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
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
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Runs one CLI operation. [line] returning `true` stops reading and kills the child; `run` then returns 0.
 * With `closeInput = false` stdin stays open until the operation ends. An input that was not fully written
 * fails the operation with `Unavailable`, even after an early stop. `RequirementsNotMet` is reported only
 * before a child process exists, so the input was certainly not delivered.
 */
internal interface ClaudeTransport {
    /** Opaque fingerprint of the native history directory; a changed store cannot resume its UUIDs. */
    val nativeStore: String get() = "default"
    suspend fun run(
        arguments: List<String>,
        input: String = "",
        workspace: WorkspaceRef? = null,
        closeInput: Boolean = true,
        hosted: ClaudeHostedTools? = null,
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
    private val workspaces: LocalWorkspaces,
) : ClaudeTransport {
    private val log = Log.tag("ClaudeProcess")
    override val nativeStore: String by lazy {
        val root = configuration.configDirectory ?: Path.of(System.getProperty("user.home"), ".claude").toString()
        MessageDigest.getInstance("SHA-256").digest(Path.of(root).toAbsolutePath().normalize().toString().toByteArray())
            .let { HexFormat.of().formatHex(it) }
    }

    override suspend fun run(
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        hosted: ClaudeHostedTools?,
        line: suspend (String) -> Boolean,
    ): Int = withContext(dispatchers.io) {
        log.d { "Starting Claude CLI operation" }
        try {
            execute(arguments, input, workspace, closeInput, hosted, line)
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
        hosted: ClaudeHostedTools?,
        line: suspend (String) -> Boolean,
    ): Int {
        val isSearchEnabled = SEARCH_BRIDGE_MARKER in arguments
        val bridgeConfig = when {
            hosted != null -> hostedConfig(hosted.endpoint, isSearchEnabled)
            isSearchEnabled -> searchConfig()
            else -> null
        }
        var instructionFile: Path? = null
        try {
            val effectiveArguments = if (hosted != null) {
                instructionFile = Files.createTempFile(configDirectory, "heartbeat-instructions-", ".txt").also {
                    restrictToOwner(it, directory = false)
                    Files.writeString(it, hosted.instructions)
                }
                claudeHostedArguments(
                    arguments,
                    checkNotNull(bridgeConfig),
                    checkNotNull(instructionFile),
                    isSearchEnabled,
                )
            } else {
                bridgeConfig?.let { claudeSearchArguments(arguments, it) } ?: arguments
            }
            val process = start(processBuilder(effectiveArguments, workspace))
            try {
                return communicate(process, input, closeInput, line)
            } finally {
                process.destroyTree()
                closeInput(process)
            }
        } finally {
            bridgeConfig?.let(Files::deleteIfExists)
            instructionFile?.let(Files::deleteIfExists)
        }
    }

    private val configDirectory by lazy { mcpConfigDirectory() }

    /** Bridge start and config file failures surface as an unavailable engine, never as a raw exception. */
    private fun searchConfig(): Path = try {
        claudeSearchConfig(searchBridge.endpoint(), configDirectory)
    } catch (e: IOException) {
        log.w(e) { "Search bridge config could not be prepared" }
        searchUnavailable()
    } catch (e: IllegalStateException) {
        log.w(e) { "Search bridge is closed" }
        searchUnavailable()
    } catch (e: UnsupportedOperationException) {
        log.w(e) { "MCP config permissions are not supported" }
        searchUnavailable()
    } catch (e: SecurityException) {
        log.w(e) { "MCP config permissions were denied" }
        searchUnavailable()
    }

    private fun searchUnavailable(): Nothing =
        throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))

    private fun hostedConfig(endpoint: AgentToolBridgeEndpoint, search: Boolean): Path = try {
        claudeHostedConfig(endpoint, if (search) searchBridge.endpoint() else null, configDirectory)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redacted()) { "Hosted MCP config could not be prepared" }
        searchUnavailable()
    }

    /** One owner-only directory; files left by a crashed run are removed once per profile transport. */
    private fun mcpConfigDirectory(): Path {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), MCP_CONFIG_DIRECTORY)
        Files.createDirectories(directory)
        restrictToOwner(directory, directory = true)
        val staleBefore = System.currentTimeMillis() - STALE_CONFIG_MILLIS
        Files.list(directory).use { files ->
            files.forEach { file ->
                // Another run may delete the file between listing and stat; each file is handled on its own.
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < staleBefore) Files.deleteIfExists(file)
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
        waitFor()
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

    private suspend fun processBuilder(arguments: List<String>, workspace: WorkspaceRef?): ProcessBuilder {
        val executable = configuration.executable
        if (executable.endsWith(".cmd", true) || executable.endsWith(".bat", true)) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        val directory = if (workspace == null) {
            configuration.workingDirectory ?: System.getProperty("user.home")
        } else {
            configuration.workspaces[workspace] ?: (if (workspaces.isAvailable) workspaces.resolve(workspace) else null)
                ?: throw EngineException(
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

/** A turn-scoped bearer; trusted instructions are passed in an owner-only file, never shell-escaped JSON. */
internal data class ClaudeHostedTools(val endpoint: AgentToolBridgeEndpoint, val instructions: String) {
    override fun toString(): String = "ClaudeHostedTools(***)"
}

/** Native coding tools remain disabled. CLI approval covers only the host, which applies its own trust gate. */
internal fun claudeHostedArguments(
    arguments: List<String>,
    config: Path,
    instructions: Path,
    search: Boolean,
): List<String> = arguments.filterNot { it == SEARCH_BRIDGE_MARKER } + listOf(
    "--permission-mode=dontAsk",
    "--allowedTools=mcp__heartbeat_tools__*" + if (search) ",mcp__heartbeat_search__*" else "",
    "--mcp-config",
    config.toString(),
    "--append-system-prompt-file",
    instructions.toString(),
)

/** Hosted coding and optional public web search share one strict MCP configuration. */
internal fun claudeHostedConfig(
    endpoint: AgentToolBridgeEndpoint,
    search: SearchBridgeEndpoint?,
    directory: Path,
): Path {
    val config = buildJsonObject {
        put(
            "mcpServers",
            buildJsonObject {
                put("heartbeat_tools", mcpServer(endpoint.url, endpoint.token))
                search?.let { put("heartbeat_search", mcpServer(it.origin, it.token)) }
            },
        )
    }
    val file = Files.createTempFile(directory, "heartbeat-mcp-", ".json")
    restrictToOwner(file, directory = false)
    Files.writeString(file, config.toString())
    return file
}

private fun mcpServer(origin: String, token: String) = buildJsonObject {
    put("type", "http")
    put("url", "${origin.trimEnd('/')}/mcp")
    put("headers", buildJsonObject { put("Authorization", "Bearer $token") })
}

/**
 * The CLI's own `WebSearch` runs at the provider and stays available next to the bridge tools. Its `WebFetch`
 * would fetch from this device without the bridge's public-host check, so pages are read only through the bridge.
 */
internal fun claudeSearchArguments(arguments: List<String>, config: Path): List<String> =
    arguments.filterNot { it == SEARCH_BRIDGE_MARKER || it == "--tools=" } + listOf(
        "--tools=$CLAUDE_SEARCH_TOOLS",
        "--allowedTools=$CLAUDE_SEARCH_TOOLS",
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
 * Restricts [path] to its owner: POSIX permissions, otherwise the ACL is replaced by one owner-only ALLOW entry
 * (Windows). The DACL is not marked protected, so inheritable entries of the parent may still apply; the
 * per-user temp directory is the remaining boundary. On a file system with neither view only the temp-directory
 * permissions protect the file. May throw [UnsupportedOperationException] or [SecurityException].
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

private const val CLAUDE_SEARCH_TOOLS =
    "WebSearch,mcp__heartbeat_search__web_search,mcp__heartbeat_search__web_fetch"
