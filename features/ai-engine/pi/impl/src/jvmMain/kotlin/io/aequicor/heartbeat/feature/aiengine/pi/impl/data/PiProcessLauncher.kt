package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebFetch
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridge
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeAttachment
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Inject
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
) {
    private val log = Log.tag("PiProcessLauncher")
    private val isMissingResourcesReported = AtomicBoolean(false)
    private val nativeSearch = AtomicReference<SearchBridgeAttachment?>(null)

    fun executable(): Path? {
        val root = System.getProperty("compose.application.resources.dir")
        if (root == null) {
            // Asked on every availability check; the missing directory is reported once.
            if (isMissingResourcesReported.compareAndSet(false, true)) {
                log.w { "Bundled Pi resources are not attached: compose.application.resources.dir is unset" }
            }
            return null
        }
        val name = if (System.getProperty("os.name").startsWith("Windows")) "pi.exe" else "pi"
        return Path.of(root, "pi", name)
    }

    suspend fun credentialFingerprint(source: AuthSource.ManagedKey): String = withContext(dispatchers.io) {
        val secret = secrets.read(SecretKey(source.secret.value))
            ?: authenticationFailure(AuthFailureReason.NotAuthenticated, source.info.id)
        secret.use { it.reveal { chars -> fingerprint(String(chars)) } }
    }

    /** Stored transcript of the native session [nativeId] of this profile, or null when Pi has none. */
    suspend fun transcript(nativeId: String): String? = withContext(dispatchers.io) {
        val owner = stores.owner as? StorageOwner.Profile ?: return@withContext null
        storage.transcript(sessionDirectory(storage.profileRoot(owner.id.value)), nativeId).also {
            log.d { if (it == null) "Pi transcript not found" else "Pi transcript found" }
        }
    }

    suspend fun start(
        source: AuthSource.ManagedKey,
        workspace: String?,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
        hosted: PiHostedTools? = null,
    ): PiConnection {
        // Fetched before the non-cancellable launch so a slow compatible server stays cancellable.
        val modelsJson = compatibleModelsJson(source)
        return launch(source, workspace, modelsJson, event, failed, hosted)
    }

    /** `models.json` for a compatible route, or null for vendor routes Pi knows natively. */
    private suspend fun compatibleModelsJson(source: AuthSource.ManagedKey): String? {
        val provider = provider(source) ?: authenticationFailure(AuthFailureReason.AuthMismatch, source.info.id)
        val protocol = provider.compatible ?: return null
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
        hosted: PiHostedTools?,
    ): PiConnection = withContext(NonCancellable + dispatchers.io) {
        val executable = executable()?.takeIf { Files.isRegularFile(it) }
            ?: piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        val provider = provider(source) ?: authenticationFailure(AuthFailureReason.AuthMismatch, source.info.id)
        val owner = stores.owner as? StorageOwner.Profile
            ?: piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        val root = storage.profileRoot(owner.id.value)
        val secret = secrets.read(SecretKey(source.secret.value))
            ?: authenticationFailure(AuthFailureReason.NotAuthenticated, source.info.id)
        // Per-process agent configuration; removed when the process exits.
        val agentDir = Files.createTempDirectory(Files.createDirectories(root.resolve("runtime")), "pi-")
        val sessionDir = Files.createDirectories(sessionDirectory(root))
        val workingDir = workspace?.let(Path::of) ?: Files.createDirectories(root.resolve("workspace"))
        val areSearchToolsEnabled = toggles.get(SearchEngineTools)
        val tools = piTools(areSearchToolsEnabled, hosted?.specifications.orEmpty().map { it.name })
        val extensions = piExtensions(agentDir, areSearchToolsEnabled, hosted != null)
        // The user opened the workspace folder explicitly, so its instructions and skills may load.
        val command = piCommand(executable, provider.id, sessionDir, extensions, tools, isProject = workspace != null)
        val builder = ProcessBuilder(command).directory(workingDir.toFile())
        val environment = builder.environment()
        retainPiEnvironment(environment)
        environment["PI_CODING_AGENT_DIR"] = agentDir.toString()
        environment["PI_SKIP_VERSION_CHECK"] = "1"
        if (hosted != null) {
            environment["HEARTBEAT_AGENT_TOOLS_URL"] = hosted.endpoint.url
            environment["HEARTBEAT_AGENT_TOOLS_TOKEN"] = hosted.endpoint.token
            environment["HEARTBEAT_AGENT_TOOL_SPECS"] = hosted.schemas()
            environment["HEARTBEAT_AGENT_TOOL_INSTRUCTIONS"] = hosted.instructions
        }
        var process: Process? = null
        try {
            if (areSearchToolsEnabled) {
                val endpoint = searchBridge.endpoint()
                environment["HEARTBEAT_SEARCH_BRIDGE_URL"] = endpoint.origin
                environment["HEARTBEAT_SEARCH_BRIDGE_TOKEN"] = endpoint.token
                attachNativeSearch()
            }
            extensions.forEach { installExtension(it.fileName.toString(), it) }
            modelsJson?.let { Files.writeString(agentDir.resolve("models.json"), it) }
            secret.use { it.reveal { chars -> environment[provider.variable] = String(chars) } }
            log.i { "Starting bundled Pi process" }
            val started = builder.start()
            process = started
            started.onExit().whenComplete { _, _ -> deleteTree(agentDir) }
            val rpc = PiRpc(started, profile.coroutineScope, dispatchers, event, failed, workingDirectory = workingDir)
            rpc.closeWith(profile.onClose(rpc::close))
            rpc
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))) {
                "Pi startup failed: ${e::class.simpleName.orEmpty()}"
            }
            // A process that started before the failure is destroyed; its exit hook removes the directory.
            process?.destroyForcibly() ?: deleteTree(agentDir)
            piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
        } finally {
            environment.remove(provider.variable)
        }
    }

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
 * Pi's command line. Extensions stay limited to the explicitly bundled ones (`--no-extensions` keeps
 * discovered, project and package extensions out), and templates and themes never load.
 *
 * A session bound to a project runs project-aware: the folder the user opened in Heartbeat grants project
 * trust (`--approve`), so Pi reads the project's context files (`AGENTS.md`/`CLAUDE.md`, which load
 * regardless of trust) and its skills (`.agents/skills/`, `.pi/skills`), as content only. A session
 * without a project keeps the fully hermetic flag set and discovers nothing.
 */
internal fun piCommand(
    executable: Path,
    provider: String,
    sessionDir: Path,
    extensions: List<Path>,
    tools: String,
    isProject: Boolean = false,
): List<String> = listOf(
    executable.toString(),
    "--mode",
    "rpc",
    "--provider",
    provider,
    "--session-dir",
    sessionDir.toString(),
    "--no-extensions",
) + extensions.flatMap { listOf("-e", it.toString()) } +
    if (isProject) {
        listOf("--approve")
    } else {
        listOf("--no-approve", "--no-skills", "--no-context-files")
    } +
    listOf(
        "--no-prompt-templates",
        "--no-themes",
        "--tools",
        tools,
    )
