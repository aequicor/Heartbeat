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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiConfiguration
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.domain.KimiBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val configuration: KimiConfiguration = KimiConfiguration(),
) : KimiBackend {
    private val log = Log.tag("KimiBackend")
    private val mutex = Mutex()
    private var runtime: KimiRuntime? = null

    override val preparation = setup.preparation

    override suspend fun prepareRuntime() = setup.prepare(this)

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

    /** The panel inspects the same CLI that sessions start, rather than treating the adapter as built in. */
    override suspend fun inspect(launch: LaunchContext): Installation = withContext(dispatchers.io) {
        val located = try {
            setup.locate(launch)
        } catch (error: EngineException) {
            log.w(error) { "Controlled Kimi runtime is missing or invalid" }
            return@withContext Installation(source = InstallSource.Missing, isRunnable = false)
        }
        val isRunnable = located.path?.let { probe(it) } == true
        located.copy(
            source = if (isRunnable) located.source else InstallSource.Missing,
            isRunnable = isRunnable,
        )
    }

    private suspend fun probe(executable: String): Boolean = try {
        val exit = probeKimiProcess(dispatchers, PROBE_TIMEOUT_MS) {
            ProcessBuilder(executable, "--version")
                .directory(java.io.File(configuration.workingDirectory ?: System.getProperty("user.home")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
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
        withKimiAuth(launch) { connection ->
            connection.logout()
            kimiLoginStatus(connection)
        } ?: LoginState.Unknown
    }

    private suspend fun withKimiAuth(
        launch: LaunchContext,
        action: suspend (io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection) -> LoginState,
    ): LoginState? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        val executable = try {
            requireNotNull(setup.locate(launch).path)
        } catch (error: EngineException) {
            log.w(error) { "Kimi sign-in needs runtime preparation" }
            return@withTimeoutOrNull LoginState.SignedOut
        }
        val transport = transports.open(AcpCommand(executable, listOf("acp"), configuration.workingDirectory))
        withKimiAuthConnection(transport, clients, profile.coroutineScope, action)
    }

    override suspend fun login(launch: LaunchContext, method: LoginMethod, session: LoginSession): LoginState {
        prepareRuntime()
        val executable = requireNotNull(setup.locate().path)
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

    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> {
        if (!accepts(source, context)) {
            throw EngineException(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
        prepareRuntime()
        val discovery = openRuntime(RuntimeIdentity(context.engine, source.info.id, source.info.revision))
        try {
            val models = discovery.discoverCatalog(context.binding).modelInfo(context.engine, context.binding)
            val limits = localApi.modelLimits()
            return models.map { model -> model.copy(contextLimitTokens = limits[model.target.model.value]) }
        } finally {
            withContext(NonCancellable) { discovery.close() }
        }
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = mutex.withLock {
        runtime?.takeUnless { it.isClosed || it.identity != identity }?.let { return@withLock it }
        runtime?.close()
        openRuntime(identity).also { runtime = it }
    }

    private suspend fun openRuntime(identity: RuntimeIdentity): KimiRuntime {
        val executable = requireNotNull(setup.locate().path)
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
    start: () -> Process,
): Int? = withContext(dispatchers.io) {
    withTimeoutOrNull(timeoutMillis) {
        val process = start()
        try {
            process.outputStream.close()
            runInterruptible { process.waitFor() }
        } finally {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.inputStream.close()
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
