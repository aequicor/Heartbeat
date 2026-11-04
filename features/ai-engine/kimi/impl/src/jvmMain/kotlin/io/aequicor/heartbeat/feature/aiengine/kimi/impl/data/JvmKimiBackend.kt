package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineCandidateInspector
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiConfiguration
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime.KimiCandidates
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime.KimiCompatibility
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime.UnsupportedKimiCandidates
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime.UnsupportedKimiCompatibility
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.domain.KimiBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.TimeMark

@ContributesBinding(ProfileScope::class, binding = binding<KimiBackend>())
@SingleIn(ProfileScope::class)
@Inject
internal class JvmKimiBackend(
    private val transports: AcpStdioTransportFactory,
    private val clients: AcpClientFactory,
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
    private val toggles: FeatureToggles,
    private val tools: ProfileAgentTools,
    private val bridge: AgentToolBridge,
    private val resources: ResourceResolver,
    private val localApi: JvmKimiLocalApi,
    private val setup: KimiRuntimeSetup,
    private val compatibilityProbe: KimiCompatibilityProbe,
    private val launches: EngineLaunchConfig,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val configuration: KimiConfiguration = KimiConfiguration(),
    private val compatibility: KimiCompatibility = UnsupportedKimiCompatibility,
    private val candidates: KimiCandidates = UnsupportedKimiCandidates,
) : KimiBackend,
    EngineCandidateInspector {
    private val log = Log.tag("KimiBackend")
    private val mutex = Mutex()
    private var runtime: KimiRuntime? = null
    private var runtimeLaunch: LaunchContext? = null

    override val preparation = setup.preparation

    /** Researched policy of this build; a target without generated facts reports null and keeps no admission. */
    override val runtimeRequirements: EngineCompatibilityRequirements? get() = compatibility.requirements()

    override suspend fun openRuntimeCandidate(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
    ): EngineRuntimeCandidate? = candidates.open(request, deadline) { executable, stamp ->
        promoteRuntime(request.identity, stamp, executable)
    }

    override suspend fun prepareRuntime() = setup.prepare(this, launches.context(KimiEngine.Id))

    override suspend fun checkRequirements(): EngineAvailability =
        if (kimiRuntimeTarget() != null) EngineAvailability.Available else EngineAvailability.UnsupportedPlatform

    override suspend fun resolveRelease(feeds: io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds) =
        kimiInstallPlan(
            feeds,
            kimiRuntimeTarget() ?: kimiInstallFailure(
                io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason.NoAssetForPlatform,
            ),
        )

    override fun compatibility(version: String) = if (version == KIMI_RUNTIME_VERSION) {
        io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility.Verified
    } else {
        io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility.Unknown
    }

    /** Reads only the selected runtime's files; a manifest is not evidence of process admission. */
    override suspend fun inspectCandidate(launch: LaunchContext): EngineCandidateInspection? {
        if (!launch.settings.executable.isNullOrBlank() ||
            launch.settings.runtime !in setOf(RuntimePreference.Managed, RuntimePreference.Bundled)
        ) {
            return null
        }
        val installation = try {
            setup.locate(launch)
        } catch (error: EngineException) {
            log.w(error) { "Selected Kimi runtime is missing or invalid" }
            Installation(InstallSource.Missing, isRunnable = false)
        }
        return EngineCandidateInspection(KimiEngine.Id, installation, compatibility = null)
    }

    /** The panel inspects the same CLI that sessions start, rather than treating the adapter as built in. */
    override suspend fun inspect(launch: LaunchContext): Installation = withContext(dispatchers.io) {
        val (runtime, source) = try {
            setup.locateControlled(launch)
        } catch (error: EngineException) {
            log.w(error) { "Controlled Kimi runtime is missing or invalid" }
            return@withContext Installation(source = InstallSource.Missing, isRunnable = false)
        }
        val located = Installation(source, KIMI_RUNTIME_VERSION, runtime.executable)
        if (!isKnownKimiRelease(runtime)) {
            log.w { "Kimi runtime does not match the pinned release" }
            return@withContext located.copy(isRunnable = false)
        }
        val isRunnable = probe(runtime.executable)
        located.copy(
            source = if (isRunnable) located.source else InstallSource.Missing,
            isRunnable = isRunnable,
        )
    }

    override suspend fun inspectCompatibility(installation: Installation, launch: LaunchContext) =
        compatibilityProbe.inspect(installation, launch)

    private suspend fun probe(executable: String): Boolean = try {
        val exit = withKimiProbeDirectory(dispatchers) { directory ->
            val command = isolatedKimiProbeCommand(executable, directory.root)
            probeKimiProcess(dispatchers, PROBE_TIMEOUT_MS, directory::processStopped) {
                ProcessBuilder(executable, "--version")
                    .directory(java.io.File(requireNotNull(command.workingDirectory)))
                    .apply {
                        environment().clear()
                        environment().putAll(command.environment)
                    }
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().also { directory.processStarted() }
            }
        }
        if (exit == null) log.w { "Kimi CLI probe timed out" }
        exit == 0
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "Kimi CLI probe failed" }
        false
    }

    override suspend fun loginStatus(launch: LaunchContext): LoginState =
        withKimiAuth(launch) { connection -> kimiLoginStatus(connection) } ?: LoginState.Unknown

    override suspend fun logout(launch: LaunchContext): LoginState = mutex.withLock {
        runtime?.close()
        runtime = null
        runtimeLaunch = null
        withKimiAuth(launch) { connection ->
            connection.logout()
            kimiLoginStatus(connection)
        } ?: LoginState.Unknown
    }

    private suspend fun withKimiAuth(
        launch: LaunchContext,
        action: suspend (io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection) -> LoginState,
    ): LoginState? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        val runtime = try {
            setup.locateControlled(launch).first
        } catch (error: EngineException) {
            log.w(error) { "Kimi sign-in needs runtime preparation" }
            return@withTimeoutOrNull LoginState.SignedOut
        }
        if (!isKnownKimiRelease(runtime)) return@withTimeoutOrNull LoginState.Unknown
        val transport = transports.open(AcpCommand(runtime.executable, listOf("acp"), configuration.workingDirectory))
        withKimiAuthConnection(transport, clients, profile.coroutineScope, action)
    }

    override suspend fun login(launch: LaunchContext, method: LoginMethod, session: LoginSession): LoginState {
        if (launch.settings.runtime == io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference.Auto) {
            setup.prepare(this, launch)
        }
        val executable = requireNotNull(setup.locateRelease(launch).path)
        val isSucceeded = try {
            runKimiLogin(dispatchers, session, LOGIN_TIMEOUT_MS, kimiTerminalLoginCommand(executable)) {
                ProcessBuilder(executable, "login")
                    .directory(java.io.File(configuration.workingDirectory ?: System.getProperty("user.home")))
                    .redirectErrorStream(true)
                    .start()
            }
        } catch (error: java.io.IOException) {
            log.w(error.redactedFailure()) { "Kimi sign-in process failed" }
            throw ManagementException(ManagementFailure.Login(LoginFailureReason.Rejected))
        }
        if (!isSucceeded) rejectLogin()
        val state = loginStatus(launch)
        if (state !is LoginState.SignedIn) rejectLogin()
        return state
    }

    private fun rejectLogin(): Nothing = throw ManagementException(ManagementFailure.Login(LoginFailureReason.Rejected))

    override fun accepts(source: AuthSource, context: EngineContext): Boolean =
        context.engine == KimiEngine.Id && source is AuthSource.CliLogin &&
            source.owner == KimiEngine.AuthOwner && source.location == KimiEngine.AuthLocation &&
            source.scope.provider.value == "kimi" && source.scope.origin.value == ORIGIN

    override fun authContext(context: EngineContext) = KimiEngine.AuthContext

    override suspend fun bind(binding: EngineBindingId, source: AuthSource) {
        if (!accepts(source, EngineContext(KimiEngine.Id, binding))) {
            throw EngineException(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
    }

    override suspend fun unbind(binding: EngineBindingId) = Unit

    /**
     * Strict isolated prompts are refused before any process, ACP session or provider request.
     *
     * The facade contract requires a fresh tool-less context, exactly one provider round, an explicit
     * provider-side output-token cap and no persisted session/configuration/history. The pinned Kimi ACP
     * connector cannot prove every property from its wire contract:
     * - ACP v1 `session/new` accepts only `cwd` and `mcpServers`. The CLI always declares its built-in
     *   native tool catalog to the model; the host can refuse every `session/request_permission`, but it
     *   cannot prove that no native tool is declared or attempted.
     * - ACP v1 `session/prompt` has no output-token field. A host-side character bound can stop reading
     *   and cancel the turn, but it is not a provider token cap; the provider may generate until that
     *   cancellation. The API contract therefore cannot be satisfied on the wire.
     * - `session/new` has no "no instructions" switch. Even an empty temporary working directory does
     *   not prove that the CLI ignores instructions from its own home/configuration.
     * - A strict session must be removable. Removal depends on `sessionCapabilities.delete`; when the
     *   capability is absent or deletion fails, closing the process does not prove that the CLI did not
     *   already persist the native session in its home.
     * - Selecting [IsolatedPrompt.target] is an ACP configuration write (`session/set_config_option`),
     *   while the strict contract forbids configuration writes.
     *
     * No best-effort answer is produced and [beforeDispatch] is never invoked, so a failed strict attempt
     * cannot fall back to a normal runtime, model discovery, history or tools. A future connector may
     * implement this method only after the pinned runtime exposes a tool-less, non-persistent session, a
     * provider token cap and a no-instructions switch, and the adapter can verify all of them before
     * dispatch.
     */
    override suspend fun answerIsolated(
        prompt: IsolatedPrompt,
        source: AuthSource,
        context: EngineContext,
        deadline: TimeMark,
        beforeDispatch: suspend () -> Unit,
    ): IsolatedAnswer {
        log.d { "Strict Kimi prompt refused: isolation unproven" }
        return IsolatedAnswer.Unavailable(IsolatedPromptFailure.IsolationUnproven)
    }

    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> {
        if (!accepts(source, context)) {
            throw EngineException(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
        // Native discovery creates an ACP session. Only the facade's admitted-runtime path may perform it.
        throw EngineException(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = mutex.withLock {
        val launch = launches.context(KimiEngine.Id)
        runtime?.takeUnless { it.isClosed || it.identity != identity || runtimeLaunch != launch }
            ?.let { return@withLock it }
        runtime?.close()
        runtime = null
        runtimeLaunch = null
        openRuntime(identity, launch).also {
            runtime = it
            runtimeLaunch = launch
        }
    }

    /**
     * Ordinary Kimi runtime for [stamp], the same construction sessions use outside admission. The controlled copy
     * this launch resolves is compared with the admitted executable and its digest before any ACP process opens,
     * and the runtime starts exactly that executable instead of resolving the launch again.
     */
    private suspend fun promoteRuntime(
        identity: RuntimeIdentity,
        stamp: EngineCompatibilityStamp,
        admittedExecutable: String?,
    ): KimiRuntime = mutex.withLock {
        require(identity.engine == KimiEngine.Id) { "Foreign engine" }
        val launch = launches.context(KimiEngine.Id)
        val executable = admittedExecutable(launch, admittedExecutable, stamp)
        val existing = runtime
        val reusable = existing?.takeIf {
            it.identity == identity && !it.isClosed && it.compatibilityStamp == stamp
        }
        if (reusable != null) return@withLock reusable
        existing?.close()
        runtime = null
        runtimeLaunch = null
        openRuntime(identity, launch, stamp, executable).also {
            runtime = it
            runtimeLaunch = launch
        }
    }

    /** The executable this launch would start, required to be the admitted one whose bytes still match the stamp. */
    private suspend fun admittedExecutable(
        launch: LaunchContext,
        admittedExecutable: String?,
        stamp: EngineCompatibilityStamp,
    ): String {
        val executable = try {
            requireNotNull(setup.locateRelease(launch).path)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Admitted Kimi executable could not be located" }
            rejectPromotion()
        }
        if (admittedExecutable == null || executable != admittedExecutable) rejectPromotion()
        val digest = try {
            kimiExecutableSha256(File(executable))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Admitted Kimi executable could not be hashed" }
            rejectPromotion()
        }
        if (!digest.equals(stamp.runtimeFingerprint, ignoreCase = true)) rejectPromotion()
        return executable
    }

    private fun rejectPromotion(): Nothing {
        log.w { "Kimi launch no longer starts the admitted executable" }
        throw EngineException(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
    }

    private suspend fun openRuntime(
        identity: RuntimeIdentity,
        launch: LaunchContext,
        stamp: EngineCompatibilityStamp? = null,
        admittedExecutable: String? = null,
    ): KimiRuntime {
        val executable = admittedExecutable ?: requireNotNull(setup.locateRelease(launch).path)
        val transport = transports.open(
            AcpCommand(
                executable,
                listOf("acp"),
                configuration.workingDirectory,
            ),
        )
        return KimiRuntime.open(
            identity,
            transport,
            clients,
            profile.coroutineScope,
            ::directory,
            toggles,
            tools,
            resources,
            localApi::usage,
            bridge,
            compatibilityStamp = stamp,
            selectedExecutable = executable,
        )
    }

    override suspend fun session(ref: SessionRef): EngineSession {
        if (ref.engine != KimiEngine.Id || ref.source != KimiEngine.SessionSource) {
            throw EngineException(
                EngineFailure.Session(
                    io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason.NotFound,
                ),
            )
        }
        return KimiStoredSession(ref)
    }

    private suspend fun directory(request: CreateSessionRequest): String {
        request.workspace?.let { workspace ->
            return workspaces.executionDirectory(workspace)
                ?: throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        return configuration.workingDirectory ?: System.getProperty("user.home")
    }

    private companion object {
        const val ORIGIN = "https://api.kimi.com"
        const val PROBE_TIMEOUT_MS = 10_000L
        const val LOGIN_TIMEOUT_MS = 300_000L
    }
}

/** Runs a bounded install probe on IO and releases the process on success, timeout and caller cancellation. */
internal suspend fun probeKimiProcess(
    dispatchers: DispatcherProvider,
    timeoutMillis: Long,
    onStopped: () -> Unit = {},
    start: () -> Process,
): Int? = withContext(dispatchers.io) {
    withTimeoutOrNull(timeoutMillis) {
        val process = start()
        try {
            process.outputStream.close()
            runInterruptible { process.waitFor() }
        } finally {
            withContext(NonCancellable) { stopKimiProbeProcess(process, onStopped) }
        }
    }
}

private suspend fun stopKimiProbeProcess(process: Process, onStopped: () -> Unit) {
    try {
        val children = try {
            process.descendants().use { it.toList() }.also { children -> children.forEach { it.destroyForcibly() } }
        } finally {
            // Even a denied descendant listing must not leave the root process running.
            process.destroyForcibly()
        }
        withContext(NonCancellable) {
            withTimeout(PROBE_STOP_TIMEOUT_MS) {
                runInterruptible {
                    process.waitFor()
                    children.forEach { it.onExit().get() }
                }
                onStopped()
            }
        }
    } finally {
        try {
            process.inputStream.close()
        } finally {
            process.errorStream.close()
        }
    }
}

/** Shell-safe command shown only when opening the provider page is unavailable. */
internal fun kimiTerminalLoginCommand(
    executable: String,
    isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
): String {
    val quoted = if (isWindows) {
        "'" + executable.replace("'", "''") + "'"
    } else {
        "'" + executable.replace("'", "'\\''") + "'"
    }
    return if (isWindows) "& $quoted login" else "$quoted login"
}

private const val PROBE_STOP_TIMEOUT_MS = 2_000L
