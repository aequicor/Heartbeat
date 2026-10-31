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
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekBackend
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val account: DeepSeekAccount,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val configuration: DeepSeekConfiguration = DeepSeekConfiguration(),
) : DeepSeekBackend {
    private val log = Log.tag("DeepSeekBackend")
    private val mutex = Mutex()
    private var runtime: DeepSeekRuntime? = null
    private val nativeHistory = DeepSeekNativeHistory(configuration, dispatchers)

    override val nativeDiscovery: ListsSessions
        get() = nativeHistory

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
            source.scope.provider.value == "deepseek" && source.scope.origin.value == ORIGIN

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
        openRuntime(identity).also { runtime = it }
    }

    private suspend fun openRuntime(identity: RuntimeIdentity): DeepSeekRuntime {
        val startup = resolveDeepSeekStartup(configuration)
        if (!startup.isRunnable) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        val transport = transports.open(
            AcpCommand(
                startup.executable,
                listOf("--profile", "acp"),
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
