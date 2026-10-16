package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparationFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.configSecretUsage
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebFetch
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridge
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeAttachment
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Inject
@SingleIn(ProfileScope::class)
internal class PiProcessLauncher(
    private val dispatchers: DispatcherProvider,
    private val secrets: SecretStore,
    private val searchBridge: SearchBridge,
    private val nativeWeb: PiNativeWeb,
    private val catalog: PiCompatibleCatalog,
    private val builtin: PiBuiltinCatalog,
    private val toggles: FeatureToggles,
    private val storage: PiStorage,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val launches: EngineLaunchConfig = EngineLaunchConfig.Default,
    private val distribution: PiRuntimeDistribution,
    private val workspaces: LocalWorkspaces,
) : PiProcesses {
    private val log = Log.tag("PiProcessLauncher")
    private val isMissingResourcesReported = AtomicBoolean(false)
    private val nativeSearch = AtomicReference<SearchBridgeAttachment?>(null)
    private val preparationLock = Mutex()
    private val preparationState = MutableStateFlow<EnginePreparation>(EnginePreparation.Idle)

    val preparation: StateFlow<EnginePreparation> = preparationState

    /** The Pi the profile's launch context starts: a custom executable, Heartbeat's newer copy or the bundled one. */
    suspend fun startup(launch: LaunchContext? = null): PiStartup =
        resolvePiStartup(launch ?: launches.context(PiEngineId), bundledExecutable(), bundledVersion())

    suspend fun executable(): Path? = startup().executable

    /** Resolves and prepares the selected executable while the caller can still cancel native startup. */
    suspend fun prepare(): PiStartup = preparationLock.withLock {
        try {
            val selected = withContext(dispatchers.io) { startup() }
            if (selected.source == InstallSource.Missing ||
                (selected.source == InstallSource.Bundled && selected.executable?.let(Files::isRegularFile) != true)
            ) {
                distribution.prepare(::publishPreparation)
            } else if (selected.source == InstallSource.Bundled && distribution.executable() == selected.executable) {
                distribution.prepare(::publishPreparation)
            }
            publishPreparation(EnginePreparation.Ready)
            withContext(dispatchers.io) { startup() }
        } catch (e: CancellationException) {
            publishPreparation(EnginePreparation.Idle)
            throw e
        } catch (e: Exception) {
            log.w(e.withoutDetails()) { "Pi runtime preparation failed" }
            publishPreparation(EnginePreparation.Failed(preparationFailure(e)))
            piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
    }

    /**
     * Runs `pi <arguments>` with [home] as Pi's agent directory, for package commands; returns the exit code, or -1
     * when it did not finish within [PACKAGE_TIMEOUT_MINUTES]. Cancelling stops the command and its child processes.
     * Output is discarded: it can name private package sources.
     */
    suspend fun runPackageCommand(home: Path, arguments: List<String>): Int {
        val startup = prepare()
        val executable = startup.executable?.takeIf { Files.isRegularFile(it) }
            ?: piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        return withContext(dispatchers.io) {
            Files.createDirectories(home)
            val builder = ProcessBuilder(listOf(executable.toString()) + arguments)
                .directory(home.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            applyPiEnvironment(builder.environment(), startup)
            builder.environment()["PI_CODING_AGENT_DIR"] = home.toString()
            builder.environment()["PI_SKIP_VERSION_CHECK"] = "1"
            log.i { "Running Pi package command=${arguments.first()}" }
            val process = builder.start()
            try {
                runInterruptible {
                    if (process.waitFor(PACKAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) process.exitValue() else -1
                }
            } finally {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }

    /** Requirements probes may report a pinned runtime as usable without downloading it. */
    suspend fun canPrepare(): Boolean = startup().source.let { source ->
        source != InstallSource.Custom && source != InstallSource.Managed && distribution.release != null
    }

    @HighFrequency
    private fun publishPreparation(value: EnginePreparation) {
        log.v { "Pi runtime preparation changed: ${value::class.simpleName.orEmpty()}" }
        preparationState.value = value
    }

    /** The build's pinned baseline version, falling back to legacy packaged metadata for development builds. */
    fun bundledVersion(): String? = distribution.release?.version ?: legacyBundledVersion()

    private fun legacyBundledVersion(): String? {
        val file = bundledExecutable()?.resolveSibling(VERSION_FILE)?.takeIf { Files.isRegularFile(it) } ?: return null
        return try {
            Files.readString(file).trim().takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_CHARS }
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "Bundled Pi version could not be read" }
            null
        }
    }

    fun bundledExecutable(): Path? = distribution.executable() ?: legacyBundledExecutable()

    private fun legacyBundledExecutable(): Path? {
        val root = System.getProperty("compose.application.resources.dir")
        if (root == null) {
            // Asked on every availability check; the missing directory is reported once.
            if (isMissingResourcesReported.compareAndSet(false, true)) {
                log.w { "Bundled Pi resources are not attached: compose.application.resources.dir is unset" }
            }
            return null
        }
        val name = if (System.getProperty("os.name").startsWith("Windows")) "pi.exe" else "pi"
        return Path.of(root, "pi", name).takeIf { Files.isRegularFile(it) }
    }

    override suspend fun credentialFingerprint(source: AuthSource.ManagedKey): String = withContext(dispatchers.io) {
        val secret = secrets.read(SecretKey(source.secret.value))
            ?: authenticationFailure(AuthFailureReason.NotAuthenticated, source.info.id)
        secret.use { it.reveal { chars -> fingerprint(String(chars)) } }
    }

    override suspend fun stop(
        owner: PiExecutionOwner,
        beginInspection: suspend () -> Boolean,
        record: suspend (PiExecutionOwner) -> PiExecutionOwner?,
    ): Boolean = PiStoredProcessStop(dispatchers).stop(owner, beginInspection, record)

    override suspend fun hasExited(owner: PiExecutionOwner): Boolean = PiStoredProcessStop(dispatchers).hasExited(owner)

    /** Stored transcript of the native session [nativeId] of this profile, or null when Pi has none. */
    override suspend fun transcript(nativeId: String): String? = withContext(dispatchers.io) {
        val owner = stores.owner as? StorageOwner.Profile
        if (owner == null) {
            log.w { "Pi transcript lookup requires profile storage" }
            return@withContext null
        }
        storage.transcript(sessionDirectory(storage.profileRoot(owner.id.value)), nativeId).also {
            log.d { if (it == null) "Pi transcript not found" else "Pi transcript found" }
        }
    }

    override suspend fun start(
        source: AuthSource.ManagedKey,
        workspace: String?,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
        plan: PiLaunchPlan?,
    ): PiConnection {
        val startup = prepare()
        // Fetched before the non-cancellable launch so a slow compatible server stays cancellable.
        val modelsJson = modelsJson(source)
        return launch(source, workspace, modelsJson, event, failed, plan ?: discoveryPlan(), startup)
    }

    private suspend fun discoveryPlan(): PiLaunchPlan = PiLaunchPlan(
        ResolvedToolPolicy(nativeOn = piTools(false).split(",").toSet()),
        null,
        if (toggles.get(SearchEngineTools)) SearchTools else emptySet(),
    )

    /** Custom model declarations or a fixed vendor endpoint; otherwise Pi uses its native catalog. */
    private suspend fun modelsJson(source: AuthSource.ManagedKey): String? {
        val provider = provider(source) ?: authenticationFailure(AuthFailureReason.AuthMismatch, source.info.id)
        val protocol = provider.compatible ?: return piVendorModelsJson(provider)
        val secret = withContext(dispatchers.io) { secrets.read(SecretKey(source.secret.value)) }
            ?: authenticationFailure(AuthFailureReason.NotAuthenticated, source.info.id)
        val key = secret.use { it.reveal { chars -> String(chars) } }
        val models = catalog.models(protocol, source.scope, key, source.info.id)
        val known = builtinCatalog()
        val inherited = piBuiltinModelsAt(known, protocol.piApi(), piCompatibleBaseUrl(protocol, source.scope))
        log.d { "Compatible route inherits Pi catalog metadata for ${models.count { it.id in inherited }} models" }
        return piModelsJson(provider, source.scope, models, inherited)
    }

    /** Pi's own catalog, or nothing when the executable or profile storage is unavailable. */
    private suspend fun builtinCatalog(): List<JsonObject> = withContext(dispatchers.io) {
        val executable = executable()?.takeIf { Files.isRegularFile(it) } ?: return@withContext emptyList()
        val owner = stores.owner as? StorageOwner.Profile ?: return@withContext emptyList()
        builtin.models(executable, storage.profileRoot(owner.id.value).resolve("runtime"))
    }

    private suspend fun launch(
        source: AuthSource.ManagedKey,
        workspace: String?,
        modelsJson: String?,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
        plan: PiLaunchPlan,
        startup: PiStartup,
    ): PiConnection = withContext(NonCancellable + dispatchers.io) {
        val executable = startup.executable?.takeIf { Files.isRegularFile(it) }
            ?: piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        val provider = provider(source) ?: authenticationFailure(AuthFailureReason.AuthMismatch, source.info.id)
        val owner = stores.owner as? StorageOwner.Profile
            ?: piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        val root = storage.profileRoot(owner.id.value)
        val secret = secrets.read(SecretKey(source.secret.value))
            ?: authenticationFailure(AuthFailureReason.NotAuthenticated, source.info.id)
        secret.use { credential ->
            // The key is owned before directory creation; all preparation failures close it too.
            val agentDir = Files.createTempDirectory(Files.createDirectories(root.resolve("runtime")), "pi-")
            var process: Process? = null
            var isConnected = false
            try {
                val sessionDir = Files.createDirectories(sessionDirectory(root))
                val workingDir = workspace?.let(Path::of) ?: Files.createDirectories(root.resolve("workspace"))
                val hosted = plan.hosted
                val areSearchToolsEnabled = plan.search.isNotEmpty()
                val (agent, flags) = configuredAgent(startup, workspace, storage.profileHome(owner.id.value), agentDir)
                val extensions = piExtensions(agentDir, areSearchToolsEnabled, hosted != null)
                val launchFlags = PiLaunchFlags(workspace != null, plan.emptySessionId, flags)
                val command = piCommand(executable, sessionDir, extensions, plan.tools(agent), launchFlags)
                val builder = ProcessBuilder(command).directory(workingDir.toFile())
                val variables = builder.environment()
                prepareEnvironment(variables, startup, agent, agentDir, plan)
                extensions.forEach { installExtension(it.fileName.toString(), it) }
                modelsJson?.let { Files.writeString(agentDir.resolve("models.json"), it) }
                writeAgentFiles(agentDir, agent)
                val started = try {
                    credential.reveal { chars -> variables[provider.variable] = String(chars) }
                    log.i { "Starting Pi process source=${startup.source}" }
                    builder.start()
                } finally {
                    variables.remove(provider.variable)
                }
                process = started
                started.onExit().whenComplete { _, _ -> deleteTree(agentDir) }
                val rpc = connect(started, workingDir, modelsJson, event, failed)
                rpc.closeWith(profile.onClose(rpc::close))
                isConnected = true
                rpc
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))) {
                    "Pi startup failed: ${e::class.simpleName.orEmpty()}"
                }
                piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
            } finally {
                if (!isConnected) {
                    // A started process removes its files on exit; pre-start failures own the directory here.
                    process?.destroyForcibly() ?: deleteTree(agentDir)
                }
            }
        }
    }

    /** Resolves trust before deriving resources, so trusted projects can discover their own extensions. */
    private suspend fun configuredAgent(
        startup: PiStartup,
        workspace: String?,
        home: Path,
        agentDir: Path,
    ): Pair<PiAgentConfig, PiFlags> {
        val content = workspace?.let { projectContent(startup, it) }
        val agent = piAgentConfig(
            startup.config,
            home,
            isProjectTrusted = startup.isConfigured && workspace != null && content == null,
            secret = ::configSecret,
        )
        return agent to agent.flags(agentDir, content)
    }

    /** The child environment is populated inside the launch cleanup boundary. */
    private fun prepareEnvironment(
        environment: MutableMap<String, String>,
        startup: PiStartup,
        agent: PiAgentConfig,
        agentDir: Path,
        plan: PiLaunchPlan,
    ) {
        applyPiEnvironment(environment, startup)
        environment.putAll(agent.environment)
        environment["PI_CODING_AGENT_DIR"] = agentDir.toString()
        environment["PI_SKIP_VERSION_CHECK"] = "1"
        // Harnesses may change during the process lifetime; inspection also passes live policy and hooks.
        environment["HEARTBEAT_GATE_ALL"] = "true"
        plan.hosted?.applyEnvironment(environment)
        if (plan.search.isNotEmpty()) {
            val endpoint = searchBridge.endpoint()
            environment["HEARTBEAT_SEARCH_BRIDGE_URL"] = endpoint.origin
            environment["HEARTBEAT_SEARCH_BRIDGE_TOKEN"] = endpoint.token
            attachNativeSearch()
        }
    }

    /**
     * Content-only project arguments while engine configuration is on and the project is not trusted; null keeps
     * full project trust (`--approve`), which is also the behavior without engine configuration.
     */
    private suspend fun projectContent(startup: PiStartup, workspace: String): List<String>? {
        if (!startup.isConfigured) return null
        val project = Path.of(workspace).normalize()
        val trusted = startup.trust.filterValues { it == ProjectTrust.Trusted }.keys
        val isTrusted = workspaces.isAvailable &&
            trusted.any { ref -> workspaces.resolve(ref)?.let { Path.of(it).normalize() } == project }
        log.d { "Pi project trust trusted=$isTrusted" }
        return if (isTrusted) null else piProjectContent(project)
    }

    /** Resolves a vault reference of the configuration; a missing entry leaves its MCP server off. */
    private suspend fun configSecret(slot: String): String? =
        secrets.readFor(configSecretUsage(PiEngineId, slot))?.use { it.reveal { chars -> String(chars) } }

    private fun writeAgentFiles(agentDir: Path, agent: PiAgentConfig) {
        agent.files.forEach { (name, text) ->
            val file = agentDir.resolve(name)
            if (name == MCP_FILE) createOwnerOnly(file)
            Files.writeString(file, text)
        }
        if (agent.files.isNotEmpty()) log.i { "Pi configuration applied files=${agent.files.size}" }
        val missing = agent.missingSecrets
        if (missing > 0) log.w { "Pi MCP servers switched off for missing secrets count=$missing" }
    }

    /**
     * Creates `mcp.json`, which may carry substituted secrets, readable by the owner only before anything is written;
     * without owner bits (Windows) the per-process directory in the app data folder guards it.
     */
    private fun createOwnerOnly(file: Path) {
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
    }

    private fun connect(
        process: Process,
        directory: Path,
        modelsJson: String?,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
    ): PiRpc = PiRpc(
        process,
        profile.coroutineScope,
        dispatchers,
        event,
        failed,
        workingDirectory = directory,
        contextWindows = piConfiguredContextWindows(modelsJson),
    )

    /**
     * Publishes Pi's native page reader on the bridge once per profile, so bridge tools can prefer it over
     * the Querit provider. The reader is in-process and stays published while the profile lives.
     */
    private fun attachNativeSearch() = synchronized(nativeSearch) {
        if (nativeSearch.get() == null) {
            nativeSearch.set(searchBridge.attach(PiFeatures(listOf(NativeWebFetch to nativeWeb))))
            profile.onClose { nativeSearch.getAndSet(null)?.detach() }
            log.i { "Pi native web reader attached to the search bridge" }
        }
    }

    private fun installExtension(name: String, target: Path) {
        val source = PiProcessLauncher::class.java.getResourceAsStream("/pi/$name")
            ?: piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        source.use { Files.copy(it, target) }
    }

    private fun deleteTree(directory: Path) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        } catch (e: IOException) {
            log.w(e) { "Pi runtime directory cleanup failed" }
        } catch (e: UncheckedIOException) {
            log.w(e) { "Pi runtime directory cleanup failed" }
        } catch (e: SecurityException) {
            log.w(e) { "Pi runtime directory cleanup was denied" }
        }
    }
}

/**
 * Leaves only the host variables an isolated Pi process needs: what a shell runs on, plus where the user's
 * software and configuration live. Credentials of other engines, endpoint overrides and unrelated host state
 * are dropped; names are matched case-insensitively because Windows spells them differently per process.
 *
 * The home and program locations are part of the contract, not a convenience: without them a host tool cannot
 * find the installation the user already has and creates a private one inside the working directory. On Windows
 * `python` resolves to the Python install manager, which without `LOCALAPPDATA` downloads a whole interpreter
 * into the workspace instead of running the system one.
 */
internal fun retainPiEnvironment(environment: MutableMap<String, String>) {
    environment.keys.retainAll { it.uppercase() in PI_HOST_ENVIRONMENT }
}

private const val VERSION_FILE = "VERSION"
private const val PACKAGE_TIMEOUT_MINUTES = 10L
private const val MAX_VERSION_CHARS = 64

private fun preparationFailure(error: Exception): EnginePreparationFailure = when (error) {
    is ReleaseException -> when (error.failure) {
        ReleaseFailure.Http, ReleaseFailure.Network -> EnginePreparationFailure.Network

        ReleaseFailure.Storage, ReleaseFailure.NotEnoughSpace -> EnginePreparationFailure.Storage

        ReleaseFailure.InvalidMetadata,
        ReleaseFailure.UntrustedSource,
        ReleaseFailure.TooLarge,
        ReleaseFailure.SizeMismatch,
        ReleaseFailure.ChecksumMismatch,
        -> EnginePreparationFailure.InvalidRuntime
    }

    is IOException, is SecurityException -> EnginePreparationFailure.Storage

    is IllegalArgumentException -> EnginePreparationFailure.InvalidRuntime

    else -> EnginePreparationFailure.Unavailable
}

private val PI_HOST_ENVIRONMENT = setOf(
    "PATH", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "OS", "PROCESSOR_ARCHITECTURE",
    "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL",
    "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "USER", "LOGNAME", "USERNAME",
    "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
)

internal fun fingerprint(value: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

/** Pi's `--tools` allowlist: extension tools must be listed explicitly or Pi disables them. */
internal fun piTools(searchTools: Boolean, hosted: List<String> = emptyList()): String {
    val base = if (System.getProperty("os.name").startsWith("Windows")) {
        "read,powershell,edit,write"
    } else {
        "read,bash,edit,write"
    }
    return (listOf(base) + (if (searchTools) listOf("web_search", "web_fetch") else emptyList()) + hosted)
        .joinToString(",")
}

/** The approval gate always loads; the search extension only while `search.engine_tools` is on. */
internal fun piExtensions(agentDir: Path, searchTools: Boolean, hosted: Boolean = false): List<Path> = buildList {
    add(agentDir.resolve(APPROVAL_EXTENSION))
    if (searchTools) add(agentDir.resolve(SEARCH_EXTENSION))
    if (hosted) add(agentDir.resolve(TOOLS_EXTENSION))
}

private const val APPROVAL_EXTENSION = "heartbeat-approval.ts"
private const val SEARCH_EXTENSION = "heartbeat-search.ts"
private const val TOOLS_EXTENSION = "heartbeat-tools.ts"

/**
 * Pi's command line. Without configuration, extensions stay limited to the explicitly bundled ones and templates
 * stay off. Profile resources and explicitly trusted projects enable discovery; themes never load.
 *
 * The command never names a provider or model: a session selects its model over RPC (`set_model`) right after
 * the start, and discovery needs none. Pi rejects `--provider` without `--model`, and a `--model` pattern would
 * read a `:` inside a model id (`qwen3:8b`) as a thinking suffix.
 *
 * With configuration enabled, project trust comes from Heartbeat's saved decision: a trusted project discovers
 * its resources and an untrusted one receives only explicit content paths under `--no-approve`. Without
 * configuration, project sessions keep the original `--approve` behavior with extension discovery disabled.
 */
internal fun piCommand(
    executable: Path,
    sessionDir: Path,
    extensions: List<Path>,
    tools: String,
    isProject: Boolean = false,
    emptySessionId: String? = null,
    flags: PiFlags = PiFlags(),
): List<String> = buildList {
    addAll(listOf(executable.toString(), "--mode", "rpc", "--session-dir", sessionDir.toString()))
    if (!flags.isDiscovering) add("--no-extensions")
    extensions.forEach { addAll(listOf("-e", it.toString())) }
    flags.builtins.forEach { addAll(listOf("-e", it)) }
    addAll(projectFlags(isProject, flags))
    emptySessionId?.let { addAll(listOf("--session-id", it)) }
    if (!flags.isPromptsLoaded) add("--no-prompt-templates")
    addAll(listOf("--no-themes", "--tools", tools))
}

/** Session-specific parts of the command line. */
internal data class PiLaunchFlags(val isProject: Boolean, val emptySessionId: String?, val flags: PiFlags)

internal fun piCommand(
    executable: Path,
    sessionDir: Path,
    extensions: List<Path>,
    tools: String,
    launch: PiLaunchFlags,
): List<String> = piCommand(
    executable,
    sessionDir,
    extensions,
    tools,
    launch.isProject,
    launch.emptySessionId,
    launch.flags,
)

/** Trust, discovery and instruction flags: a project session reads the project, a detached one stays hermetic. */
private fun projectFlags(isProject: Boolean, flags: PiFlags): List<String> = buildList {
    val isContextRead = isProject && flags.hasProjectContext
    when {
        !isProject -> add("--no-approve")

        flags.projectContent == null -> add("--approve")

        else -> {
            add("--no-approve")
            addAll(flags.projectContent)
        }
    }
    if (!isProject && !flags.isSkillsLoaded) add("--no-skills")
    if (!isContextRead) add("--no-context-files")
    flags.instructions?.takeIf { !isContextRead }?.let { addAll(listOf("--append-system-prompt", it)) }
}

/**
 * Launch flags the profile configuration changes; the defaults keep the hermetic command line. [instructions] is the
 * profile's `AGENTS.md`, passed explicitly where context discovery is off.
 */
internal data class PiFlags(
    val builtins: List<String> = emptyList(),
    val isDiscovering: Boolean = false,
    val isSkillsLoaded: Boolean = false,
    val isPromptsLoaded: Boolean = false,
    val hasProjectContext: Boolean = true,
    val instructions: String? = null,
    /** Arguments loading an untrusted project's content explicitly; null trusts the project (`--approve`). */
    val projectContent: List<String>? = null,
) {
    override fun toString(): String = "PiFlags(builtins=$builtins, discovering=$isDiscovering)"
}

/** The launch flags of this configuration for a process whose agent directory is [agentDir]. */
internal fun PiAgentConfig.flags(agentDir: Path, projectContent: List<String>? = null): PiFlags = PiFlags(
    builtins = builtins,
    isDiscovering = isDiscovering,
    isSkillsLoaded = isSkillsLoaded,
    isPromptsLoaded = isPromptsLoaded,
    hasProjectContext = hasProjectContext,
    instructions = agentDir.resolve(AGENTS_FILE).toString().takeIf { hasInstructions },
    projectContent = projectContent,
)

/**
 * What an untrusted project still contributes, loaded explicitly under `--no-approve`: its skills, prompt templates
 * and system prompt files. Its settings, MCP servers, extensions and packages stay off; `AGENTS.md` loads anyway.
 */
internal fun piProjectContent(project: Path): List<String> = buildList {
    val pi = project.resolve(".pi")
    listOf(pi.resolve("skills"), project.resolve(".agents").resolve("skills")).filter(Files::isDirectory).forEach {
        addAll(listOf("--skill", it.toString()))
    }
    pi.resolve("prompts").takeIf(Files::isDirectory)?.let { addAll(listOf("--prompt-template", it.toString())) }
    pi.resolve("SYSTEM.md").takeIf(Files::isRegularFile)?.let { addAll(listOf("--system-prompt", it.toString())) }
    pi.resolve("APPEND_SYSTEM.md").takeIf(Files::isRegularFile)?.let {
        addAll(listOf("--append-system-prompt", it.toString()))
    }
}
