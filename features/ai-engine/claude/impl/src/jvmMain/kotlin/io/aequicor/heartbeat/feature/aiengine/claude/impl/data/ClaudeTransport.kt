package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
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

    /** Owns a bidirectional operation until [session] ends. The caller decides when stdin should close. */
    suspend fun duplex(
        arguments: List<String>,
        workspace: WorkspaceRef? = null,
        hosted: ClaudeHostedTools? = null,
        session: suspend (ClaudeDuplex) -> Unit,
    ): Int = throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))

    /**
     * This transport bound to [launch], or to the profile's current launch context when null. A runtime keeps its
     * pinned transport for life, so its sessions never move to another executable or native history mid-way.
     */
    suspend fun pinned(launch: LaunchContext? = null): ClaudeTransport = this

    /** Version of this transport's captured executable, without consulting later launch settings. */
    suspend fun version(): String? = null

    /** The executable [launch] would start and its version; never signs in or starts a session. */
    suspend fun locate(launch: LaunchContext): Installation = Installation(InstallSource.Missing)
}

/**
 * Native executable only, bounded UTF-8 frames, discarded stderr and an allowlisted host environment.
 * Every operation is its own process, so operations run concurrently; sessions serialize their own turns.
 * Unless pinned, each operation starts the CLI as the profile's current launch context describes.
 */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class ProcessClaudeTransport(
    private val dispatchers: DispatcherProvider,
    private val configuration: ClaudeConfiguration = ClaudeConfiguration(),
    private val searchBridge: SearchBridge,
    private val workspaces: LocalWorkspaces,
    private val launches: EngineLaunchConfig = EngineLaunchConfig.Default,
) : ClaudeTransport {
    private val log = Log.tag("ClaudeProcess")
    override val nativeStore: String by lazy { claudeNativeStore(configuration.configDirectory) }

    override suspend fun run(
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        hosted: ClaudeHostedTools?,
        line: suspend (String) -> Boolean,
    ): Int = run(startup(null), arguments, input, workspace, closeInput, hosted, line)

    override suspend fun duplex(
        arguments: List<String>,
        workspace: WorkspaceRef?,
        hosted: ClaudeHostedTools?,
        session: suspend (ClaudeDuplex) -> Unit,
    ): Int = duplex(startup(null), arguments, workspace, hosted, session)

    suspend fun duplex(
        startup: ClaudeStartup,
        arguments: List<String>,
        workspace: WorkspaceRef?,
        hosted: ClaudeHostedTools?,
        session: suspend (ClaudeDuplex) -> Unit,
    ): Int = withContext(dispatchers.io) {
        try {
            withProcess(startup, arguments, workspace, hosted) { communicateDuplex(it, session) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ensureActive()
            log.w(e.redacted()) { "Claude duplex process IO failed" }
            throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
        }
    }

    override suspend fun pinned(launch: LaunchContext?): ClaudeTransport = PinnedClaudeTransport(this, startup(launch))

    override suspend fun locate(launch: LaunchContext): Installation {
        val startup = startup(launch)
        val isFound = startup.source != InstallSource.Missing
        val version = if (isFound && startup.isRunnable) version(startup) else null
        log.i { "Claude located source=${startup.source} version=${version ?: "unknown"}" }
        return Installation(startup.source, version, startup.executable.takeIf { isFound }, startup.isRunnable)
    }

    private suspend fun startup(launch: LaunchContext?): ClaudeStartup =
        resolveClaudeStartup(launch ?: launches.context(ClaudeEngine.Id), configuration)

    /** `claude --version`, bounded; an executable that does not answer has no known version. */
    override suspend fun version(): String? = version(startup(null))

    suspend fun version(startup: ClaudeStartup): String? = try {
        var version: String? = null
        withProbeTimeout {
            run(startup, listOf("--version"), "", null, true, null) { line ->
                version = version ?: parseClaudeVersion(line)
                false
            }
        }
        version
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e.redacted()) { "Claude version could not be read" }
        null
    }

    @Suppress("LongParameterList") // One CLI operation: the startup plus the run contract's parameters.
    suspend fun run(
        startup: ClaudeStartup,
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        hosted: ClaudeHostedTools?,
        line: suspend (String) -> Boolean,
    ): Int = withContext(dispatchers.io) {
        log.d { "Starting Claude CLI operation" }
        try {
            withProcess(startup, arguments, workspace, hosted) { communicate(it, input, closeInput, line) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Killing the child on cancellation or timeout breaks its pipes; that is not an IO failure.
            ensureActive()
            log.w(e.redacted()) { "Claude process IO failed" }
            throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
        }
    }

    private suspend fun withProcess(
        startup: ClaudeStartup,
        arguments: List<String>,
        workspace: WorkspaceRef?,
        hosted: ClaudeHostedTools?,
        operation: suspend (Process) -> Int,
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
                    claudeToolFlags(arguments),
                )
            } else {
                bridgeConfig?.let { claudeSearchArguments(arguments, it, claudeToolFlags(arguments)) } ?: arguments
            }
            val process = start(processBuilder(startup, effectiveArguments, workspace))
            try {
                return operation(process)
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

    private suspend fun communicateDuplex(process: Process, session: suspend (ClaudeDuplex) -> Unit): Int =
        coroutineScope {
            val pipe = ClaudeDuplexPipe(
                this,
                process.outputStream.bufferedWriter(Charsets.UTF_8),
                process.inputStream.bufferedReader(Charsets.UTF_8),
            )
            val exchange = async { session(pipe) }
            val exit = async { process.waitFor() }
            try {
                exchange.await()
                pipe.closeInput()
                pipe.reader.await()
                pipe.writer.await()
                exit.await()
            } finally {
                pipe.revoke()
                // Do this before coroutineScope joins the reader/writer: either may be blocked in native IO.
                process.destroyTree()
                closeInput(process)
            }
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

    private suspend fun processBuilder(
        startup: ClaudeStartup,
        arguments: List<String>,
        workspace: WorkspaceRef?,
    ): ProcessBuilder {
        if (!startup.isRunnable) {
            log.w { "Claude executable is missing or not runnable source=${startup.source}" }
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
        val builder = ProcessBuilder(listOf(startup.executable) + arguments).directory(File(directory))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val environment = builder.environment()
        val allowed = claudeEnvironment(environment.toMap(), startup)
        environment.clear()
        environment.putAll(allowed)
        return builder
    }
}

/** [base] with one resolved startup; the native history is the one of its config directory. */
private class PinnedClaudeTransport(private val base: ProcessClaudeTransport, private val startup: ClaudeStartup) :
    ClaudeTransport {
    override val nativeStore: String = claudeNativeStore(startup.configDirectory)

    override suspend fun run(
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        hosted: ClaudeHostedTools?,
        line: suspend (String) -> Boolean,
    ): Int = base.run(startup, arguments, input, workspace, closeInput, hosted, line)

    override suspend fun duplex(
        arguments: List<String>,
        workspace: WorkspaceRef?,
        hosted: ClaudeHostedTools?,
        session: suspend (ClaudeDuplex) -> Unit,
    ): Int = base.duplex(startup, arguments, workspace, hosted, session)

    override suspend fun pinned(launch: LaunchContext?): ClaudeTransport = launch?.let { base.pinned(it) } ?: this

    override suspend fun locate(launch: LaunchContext): Installation = base.locate(launch)

    override suspend fun version(): String? = base.version(startup)
}

/**
 * A turn-scoped bearer; trusted instructions are passed in an owner-only file, never shell-escaped JSON.
 * [isProject] is false for detached tools of a session without a project.
 */
internal data class ClaudeHostedTools(
    val endpoint: AgentToolBridgeEndpoint,
    val instructions: String,
    val isProject: Boolean = true,
) {
    override fun toString(): String = "ClaudeHostedTools(***)"
}

/** Adds host preapproval without changing the native set selected by the turn policy. */
internal fun claudeHostedArguments(
    arguments: List<String>,
    config: Path,
    instructions: Path,
    tools: ClaudeToolFlags,
): List<String> = withoutToolOptions(arguments).filterNot { it.startsWith("--permission-mode=") } +
    tools.copy(allowed = tools.allowed + "mcp__${HOSTED_TOOLS_SERVER}__*").arguments() + listOf(
        if ("--permission-prompt-tool=stdio" in arguments) "--permission-mode=default" else "--permission-mode=dontAsk",
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
                put(HOSTED_TOOLS_SERVER, mcpServer(endpoint.url, endpoint.token))
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
internal fun claudeSearchArguments(arguments: List<String>, config: Path, tools: ClaudeToolFlags): List<String> =
    withoutToolOptions(arguments) + tools.arguments() + listOf("--mcp-config", config.toString())

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

internal fun BufferedReader.readFrame(): String? {
    val result = StringBuilder()
    var next = read()
    while (next != -1 && next != '\n'.code) {
        if (result.length >= MAX_FRAME_CHARS) protocolFailure()
        result.append(next.toChar())
        next = read()
    }
    return if (next == -1 && result.isEmpty()) null else result.toString().trimEnd('\r')
}

internal const val MAX_FRAME_CHARS = 2 * 1024 * 1024

/** Replace the complete generated tool selection; no earlier native permission list can survive rebuilding. */
private fun withoutToolOptions(arguments: List<String>): List<String> = arguments.filterNot {
    it == SEARCH_BRIDGE_MARKER || it.startsWith("--tools=") || it.startsWith("--allowedTools=") ||
        it.startsWith("--disallowedTools=")
}
