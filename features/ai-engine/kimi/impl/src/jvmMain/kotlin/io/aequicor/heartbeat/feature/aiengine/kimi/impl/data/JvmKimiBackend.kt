package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

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
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiConfiguration
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.domain.KimiBackend
import kotlinx.coroutines.CancellationException
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
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val configuration: KimiConfiguration = KimiConfiguration(),
) : KimiBackend {
    private val log = Log.tag("KimiBackend")
    private val mutex = Mutex()
    private var runtime: KimiRuntime? = null

    override suspend fun checkRequirements(): EngineAvailability = try {
        val exit = probeKimiProcess(dispatchers, PROBE_TIMEOUT_MS) {
            ProcessBuilder(configuration.executable, "--version")
                .directory(java.io.File(configuration.workingDirectory ?: System.getProperty("user.home")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        if (exit == null) log.w { "Kimi CLI probe timed out" }
        if (exit == 0) EngineAvailability.Available else unavailable()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "Kimi CLI probe failed" }
        unavailable()
    }

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
        val target = io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget(
            context.engine,
            context.binding,
            io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId("kimi-for-coding"),
        )
        return listOf(ModelInfo(target, "Kimi for Coding", contextLimitTokens = 1_000_000))
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = mutex.withLock {
        runtime?.takeUnless { it.isClosed || it.identity != identity }?.let { return@withLock it }
        runtime?.close()
        val transport = transports.open(
            AcpCommand(
                configuration.executable,
                listOf("acp"),
                configuration.workingDirectory,
            ),
        )
        KimiRuntime.open(identity, transport, clients, profile.coroutineScope, ::directory).also { runtime = it }
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

    private fun unavailable() = EngineAvailability.Unavailable(
        EngineFailure.Engine(EngineFailureReason.RequirementsNotMet),
    )

    private companion object {
        const val ORIGIN = "https://api.kimi.com"
        const val PROBE_TIMEOUT_MS = 10_000L
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
