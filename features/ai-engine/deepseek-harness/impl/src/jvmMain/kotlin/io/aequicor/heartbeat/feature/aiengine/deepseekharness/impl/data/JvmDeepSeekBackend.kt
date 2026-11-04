package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekAuthentication
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekLogin
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime.DshCandidates
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime.DshCompatibility
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime.UnsupportedDshCandidates
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime.UnsupportedDshCompatibility
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekBackend
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark

@ContributesBinding(ProfileScope::class, binding = binding<DeepSeekBackend>())
@ContributesBinding(ProfileScope::class, binding = binding<DeepSeekAuthentication>())
@SingleIn(ProfileScope::class)
@Inject
internal class JvmDeepSeekBackend(
    private val transports: AcpStdioTransportFactory,
    private val clients: AcpClientFactory,
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
    private val tools: ProfileAgentTools,
    private val bridge: AgentToolBridge,
    private val resources: ResourceResolver,
    private val account: DeepSeekAccount,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val configuration: DeepSeekConfiguration = DeepSeekConfiguration(),
    private val compatibility: DshCompatibility = UnsupportedDshCompatibility,
    private val candidates: DshCandidates = UnsupportedDshCandidates,
) : DeepSeekBackend {
    private val log = Log.tag("DeepSeekBackend")
    private val mutex = Mutex()
    private var runtime: DeepSeekRuntimeGroup? = null
    private val nativeHistory = DeepSeekNativeHistory(configuration, dispatchers)

    override val nativeDiscovery: ListsSessions
        get() = nativeHistory

    override val nativeTrees = DeepSeekSessionTrees(nativeHistory)

    /** Researched policy of this build; a target without generated facts reports null and keeps no admission. */
    override val runtimeRequirements: EngineCompatibilityRequirements? get() = compatibility.requirements()

    override suspend fun openRuntimeCandidate(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
    ): EngineRuntimeCandidate? = candidates.open(request, deadline) { stamp ->
        promoteRuntime(request.identity, stamp)
    }

    override suspend fun inspect(): DeepSeekLogin = account.inspect(configuration.homeDirectory)

    override suspend fun checkRequirements(): EngineAvailability = try {
        val startup = resolveDeepSeekStartup(configuration)
        if (!startup.isRunnable) {
            return EngineAvailability.Unavailable(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        val exit = probeDeepSeekProcess(dispatchers, PROBE_TIMEOUT_MS) {
            ProcessBuilder(startup.executable, "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        if (exit == 0) {
            EngineAvailability.Available
        } else {
            EngineAvailability.Unavailable(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "DeepSeek Harness installation probe failed" }
        EngineAvailability.Unavailable(EngineFailure.Engine(EngineFailureReason.Unavailable))
    }

    override fun accepts(source: AuthSource, context: EngineContext): Boolean =
        context.engine == DeepSeekEngine.Id && source is AuthSource.CliLogin &&
            source.owner == DeepSeekEngine.AuthOwner && source.location == DeepSeekEngine.AuthLocation &&
            source.scope.basePath == null && source.scope.provider.value == "deepseek" &&
            source.scope.origin.value == ORIGIN

    override suspend fun sourceRevision(source: AuthSource) = run {
        require(accepts(source, EngineContext(DeepSeekEngine.Id, EngineBindingId("revision-check"))))
        inspect().source.info.revision
    }

    override fun authContext(context: EngineContext) = DeepSeekEngine.AuthContext

    override suspend fun bind(binding: EngineBindingId, source: AuthSource) {
        if (!accepts(source, EngineContext(DeepSeekEngine.Id, binding))) {
            throw EngineException(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
    }

    override suspend fun unbind(binding: EngineBindingId) = Unit

    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> {
        if (!accepts(source, context)) {
            throw EngineException(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
        val discovery = openRuntime(RuntimeIdentity(context.engine, source.info.id, source.info.revision))
        try {
            return discovery.discoverCatalog(context.binding).modelInfo(context.engine, context.binding)
        } finally {
            withContext(NonCancellable) { discovery.close() }
        }
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = mutex.withLock {
        runtime?.takeUnless { it.isClosed || it.identity != identity }?.let { return@withLock it }
        runtime?.close()
        openGroup(identity).also { runtime = it }
    }

    private fun openGroup(
        identity: RuntimeIdentity,
        stamp: EngineCompatibilityStamp? = null,
        selectedExecutable: String? = null,
    ): DeepSeekRuntimeGroup = DeepSeekRuntimeGroup(
        identity,
        DeepSeekSessionTrees(nativeHistory),
        compatibilityStamp = stamp,
        selectedExecutable = selectedExecutable,
    ) { retired ->
        openRuntime(
            identity,
            isSingleSession = true,
            onRetired = retired,
            compatibilityStamp = stamp,
            selectedExecutable = selectedExecutable,
        )
    }

    /**
     * Ordinary DSH runtime for [stamp], the same construction sessions use outside admission. The assessed
     * executable is pinned so every child process of this runtime starts from the fingerprinted launcher; a changed
     * configuration is not silently substituted and fails closed before any runtime is transferred.
     */
    private suspend fun promoteRuntime(
        identity: RuntimeIdentity,
        stamp: EngineCompatibilityStamp,
    ): DeepSeekRuntimeGroup = mutex.withLock {
        require(identity.engine == DeepSeekEngine.Id) { "Foreign engine" }
        val existing = runtime
        val reusable = existing?.takeIf {
            it.identity == identity && !it.isClosed && it.compatibilityStamp == stamp
        }
        if (reusable != null) return@withLock reusable
        existing?.close()
        runtime = null
        val startup = resolveDeepSeekStartup(configuration)
        if (!startup.isRunnable) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        openGroup(identity, stamp, startup.executable).also { runtime = it }
    }

    private suspend fun openRuntime(
        identity: RuntimeIdentity,
        isSingleSession: Boolean = false,
        onRetired: suspend (DeepSeekRuntime) -> Unit = {},
        compatibilityStamp: EngineCompatibilityStamp? = null,
        selectedExecutable: String? = null,
    ): DeepSeekRuntime {
        val startup = resolveDeepSeekStartup(configuration)
        val executable = selectedExecutable ?: startup.executable
        if (selectedExecutable == null && !startup.isRunnable) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        val transport = transports.open(
            AcpCommand(
                executable,
                DeepSeekAcpPatch.arguments(),
                configuration.workingDirectory,
                dshEnvironment(startup.homeDirectory),
            ),
        )
        return DeepSeekRuntime.open(
            identity,
            transport,
            clients,
            profile.coroutineScope,
            ::directory,
            tools,
            resources,
            sessionTrees = DeepSeekSessionTrees(nativeHistory),
            bridge = bridge,
            isSingleSession = isSingleSession,
            onRetired = onRetired,
            compatibilityStamp = compatibilityStamp,
            selectedExecutable = executable,
        )
    }

    override suspend fun session(ref: SessionRef): EngineSession {
        if (ref.engine != DeepSeekEngine.Id || ref.source != DeepSeekEngine.SessionSource) {
            throw EngineException(
                EngineFailure.Session(io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason.NotFound),
            )
        }
        return DeepSeekStoredSession(ref, nativeHistory.stored(ref))
    }

    private suspend fun directory(request: CreateSessionRequest): String {
        request.workspace?.let { workspace ->
            return workspaces.executionDirectory(workspace)
                ?: throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        return configuration.workingDirectory ?: System.getProperty("user.home")
    }

    private companion object {
        const val ORIGIN = "https://api.deepseek.com"
        const val PROBE_TIMEOUT_MS = 10_000L
    }
}

/** Runs a bounded install probe on IO and releases the process on success, timeout and caller cancellation. */
internal suspend fun probeDeepSeekProcess(
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
