package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DeepSeekHost
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshExternalFingerprint
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.probeDeepSeekVersion
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.resolveDeepSeekStartup
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.TimeMark

/**
 * Explicit, credential-free inspect of the exact selected DSH launcher. It starts the ordinary ACP profile in an
 * isolated temporary home, agents home, work and temp directory with a cleared environment, reads the launcher's
 * own runtime line with `--version` and sends only `initialize`: no login, session, tools or prompt. A handshake
 * establishes wire/ABI facts, never a native-session baseline; the runtime line comes from `--version` because
 * DSH's ACP `agentInfo.version` is adapter metadata, not the engine's version.
 */
internal class DshCompatibilityProbe internal constructor(
    private val configuration: DeepSeekConfiguration,
    private val host: DeepSeekHost,
    private val transports: AcpStdioTransportFactory,
    private val clients: AcpClientFactory,
    private val dispatchers: DispatcherProvider,
) : DshBaselineProbe {
    @Inject
    constructor(
        transports: AcpStdioTransportFactory,
        clients: AcpClientFactory,
        dispatchers: DispatcherProvider,
    ) : this(DeepSeekConfiguration(), DeepSeekHost(), transports, clients, dispatchers)

    private val log = Log.tag("DshCompatibilityProbe")

    /**
     * Structured observation of exactly [installation], using the adapter's own resolved launch without
     * substitution. The manager's launch snapshot does not select the DSH executable, so it is not accepted here.
     */
    suspend fun inspect(installation: Installation): EngineCompatibilityObservation =
        inspectWithin(installation, PROBE_TIMEOUT_MILLIS)

    /**
     * Admission baseline over the same inspect. A completed handshake confirms only the ABI it actually advertised;
     * a protocol/preflight failure rejects the baseline; a timeout or an otherwise unobserved check stays
     * inconclusive. Caller cancellation is never caught and always propagates.
     */
    override suspend fun run(installation: Installation, deadline: TimeMark): DshBaselineReceipt {
        if (deadline.hasPassedNow()) return unobservedBaseline()
        val remaining = (-deadline.elapsedNow()).inWholeMilliseconds.coerceIn(1L, PROBE_TIMEOUT_MILLIS)
        return try {
            val observation = inspectWithin(installation, remaining)
            DshBaselineReceipt(DshBaselineOutcome.Confirmed, observation.evidence.identity.runtime)
        } catch (error: AcpException) {
            log.w(error) { "DSH admission handshake failed" }
            rejectedBaseline()
        } catch (error: EngineException) {
            log.w(error) { "DSH admission preflight failed" }
            if (error.failure == EngineFailure.Transport(TransportFailureReason.Timeout)) {
                unobservedBaseline()
            } else {
                rejectedBaseline()
            }
        } catch (error: IOException) {
            log.w(IllegalStateException(error.javaClass.simpleName)) { "DSH admission baseline could not start" }
            unobservedBaseline()
        } catch (error: SecurityException) {
            log.w(IllegalStateException(error.javaClass.simpleName)) { "DSH admission baseline could not start" }
            unobservedBaseline()
        }
    }

    private suspend fun inspectWithin(
        installation: Installation,
        timeoutMillis: Long,
    ): EngineCompatibilityObservation = withTimeoutOrNull(timeoutMillis) {
        inspectExactly(installation)
    } ?: throw EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))

    private suspend fun inspectExactly(installation: Installation): EngineCompatibilityObservation {
        val startup = resolveDeepSeekStartup(configuration, host)
        val isSameSource = startup.source == installation.source && startup.executable == installation.path
        if (!startup.isRunnable || startup.source !in PROBE_SOURCES || !isSameSource) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        return withDshProbeDirectory(dispatchers) { directory ->
            val version = observeVersion(startup.executable, directory)
            val initialized = negotiate(startup.executable, directory)
            val fingerprint = withContext(dispatchers.io) { DshExternalFingerprint.of(startup.executable) }
            observation(initialized, version, fingerprint)
        }
    }

    private suspend fun observeVersion(executable: String, directory: DshProbeDirectory): String? {
        directory.processStarted()
        return try {
            probeDeepSeekVersion(
                dispatchers = dispatchers,
                executable = executable,
                timeoutMillis = PROBE_COMMAND_TIMEOUT_MILLIS,
                workingDirectory = directory.work.toFile(),
                environment = isolatedDshProbeEnvironment(directory),
            )
        } finally {
            directory.processStopped()
        }
    }

    private suspend fun negotiate(executable: String, directory: DshProbeDirectory): AcpInitialization {
        directory.processStarted()
        val transport = try {
            transports.open(isolatedDshProbeCommand(executable, directory))
        } catch (error: AcpException.LaunchFailed) {
            directory.processStopped()
            throw error
        }
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val scope = CoroutineScope(currentCoroutineContext() + owner)
        try {
            val connection = clients.connect(
                transport,
                scope,
                object : AcpClientHandler {
                    override suspend fun onSessionUpdate(update: AcpSessionUpdate) = Unit
                },
            )
            return connection.initialize(AcpImplementation("heartbeat-inspect", "1"))
        } finally {
            // Closing the transport unblocks owned requests even if initialize is still running after cancellation.
            try {
                transport.close()
            } finally {
                withContext(NonCancellable) {
                    try {
                        transport.closeAndAwait()
                        directory.processStopped()
                    } finally {
                        owner.cancelAndJoin()
                    }
                }
            }
        }
    }

    private fun observation(
        initialized: AcpInitialization,
        version: String?,
        fingerprint: String?,
    ): EngineCompatibilityObservation = EngineCompatibilityObservation(
        requirements = EngineCompatibilityRequirements(
            engine = DeepSeekEngine.Id,
            heartbeatVersions = null,
            connectorVersions = null,
            minimumRuntime = null,
            protocols = setOf(DshJvmCompatibility.PROTOCOL),
            configurationSchemas = null,
            historySchemas = null,
            nativeTargets = DshJvmCompatibility.nativeTargets,
            baselineCapabilities = DshJvmCompatibility.baselineCapabilities,
        ),
        evidence = EngineCompatibilityEvidence(
            identity = EngineCompatibilityIdentity(
                engine = DeepSeekEngine.Id,
                runtime = version,
                protocol = initialized.protocolVersion.takeIf { it > 0 }?.let {
                    EngineProtocolIdentity.Negotiated("acp", it)
                },
                nativeTarget = dshNativeTarget(host),
                runtimeFingerprint = fingerprint,
            ),
            isBaselineConfirmed = true,
            isProvenanceConfirmed = DshExternalFingerprint.isExternal(fingerprint),
            capabilities = DshJvmCompatibility.baselineCapabilities,
        ),
    )
}

private fun unobservedBaseline() = DshBaselineReceipt(DshBaselineOutcome.Unobserved)

private fun rejectedBaseline() = DshBaselineReceipt(DshBaselineOutcome.Rejected)

private val PROBE_SOURCES = setOf(InstallSource.Custom, InstallSource.System)

private const val PROBE_TIMEOUT_MILLIS = 15_000L
private const val PROBE_COMMAND_TIMEOUT_MILLIS = 10_000L
