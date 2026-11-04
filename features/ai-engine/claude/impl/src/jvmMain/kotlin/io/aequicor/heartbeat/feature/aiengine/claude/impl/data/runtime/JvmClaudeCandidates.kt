package io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeHost
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.claudeExecutableSha256
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.claudeReleasePlatform
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.resolveClaudeStartup
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.verifyClaudeManagedCopy
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeDesktopSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/**
 * Restricted candidate of the desktop Claude adapter. Acquisition verifies the exact selected executable against
 * the managed copy's recorded executable digest and then runs only the login-free stream-json initialize baseline;
 * it reads no credentials, user home, project configuration, tools or history and sends no model prompt. Promotion
 * hands over the ordinary runtime the adapter builds for the assessed stamp and is allowed at most once.
 */
@ContributesBinding(ProfileScope::class)
internal class JvmClaudeCandidates internal constructor(
    private val configuration: ClaudeConfiguration,
    private val platform: PlatformInfo,
    private val host: ClaudeHost,
    private val desktop: ClaudeDesktopSwitch,
    private val dispatchers: DispatcherProvider,
    private val probe: ClaudeBaselineProbe,
) : ClaudeCandidates {
    @Inject
    constructor(
        platform: PlatformInfo,
        dispatchers: DispatcherProvider,
        configuration: ClaudeConfiguration = ClaudeConfiguration(),
        desktop: ClaudeDesktopSwitch = ClaudeDesktopSwitch.On,
    ) : this(configuration, platform, ClaudeHost(), desktop, dispatchers, JvmClaudeBaselineProbe(dispatchers))

    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? {
        currentCoroutineContext().ensureActive()
        val selected = desktopTarget(request)?.let { target -> selection(request, target, deadline) }
            ?: return null
        return ClaudeRuntimeCandidate(
            request,
            ClaudeJvmCompatibility.evidence(selected.artifact, selected.baseline, selected.isProvenanceConfirmed),
            selected.executable,
            dispatchers,
            promote,
        )
    }

    private fun desktopTarget(request: EngineRuntimeCandidateRequest): String? {
        if (request.identity.engine != ClaudeEngine.Id ||
            platform.host.name !in ClaudeJvmCompatibility.desktopTargets
        ) {
            return null
        }
        return claudeReleasePlatform(host)
    }

    /**
     * A staged install candidate is inspected exactly as supplied; no managed, host-app or system copy is
     * substituted. Otherwise the selected launch source decides: only a managed copy with a recorded executable
     * digest can establish provenance. The desktop app, system and custom executables have no pin and stay unknown.
     */
    private suspend fun selection(
        request: EngineRuntimeCandidateRequest,
        target: String,
        deadline: TimeMark,
    ): Selection? {
        if (request.launch.isInstallCandidate) {
            val managed = request.launch.managed ?: return null
            return managed(managed, target, deadline)
        }
        val startup = withContext(dispatchers.io) {
            resolveClaudeStartup(request.launch, configuration, host, isHostAppEnabled = desktop.isEnabled())
        }
        return when (startup.source) {
            InstallSource.Managed -> {
                val managed = request.launch.managed?.takeIf { it.executable == startup.executable }
                if (managed == null) unknown() else managed(managed, target, deadline)
            }

            InstallSource.Custom,
            InstallSource.System,
            InstallSource.HostApp,
            -> unknown()

            InstallSource.Bundled,
            InstallSource.BuiltIn,
            InstallSource.Missing,
            -> null
        }
    }

    private suspend fun managed(managed: ManagedInstall, target: String, deadline: TimeMark): Selection {
        val isVerified = verifyClaudeManagedCopy(managed, dispatchers.io)
        if (isVerified != true) {
            // Without a matching recorded executable digest nothing about the artifact is established.
            return Selection(
                ClaudeArtifactFacts(runtime = null, nativeTarget = target, runtimeFingerprint = null),
                baseline = null,
                isProvenanceConfirmed = isVerified,
                executable = managed.executable,
            )
        }
        val baseline = baseline(managed.executable, deadline)
        return Selection(
            ClaudeArtifactFacts(
                runtime = managed.version,
                nativeTarget = target,
                runtimeFingerprint = managed.executableSha256,
            ),
            baseline,
            isProvenanceConfirmed = true,
            executable = managed.executable,
        )
    }

    private fun unknown(): Selection = Selection(
        ClaudeArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null),
        baseline = null,
        isProvenanceConfirmed = null,
        executable = null,
    )

    private suspend fun baseline(executable: String, deadline: TimeMark): ClaudeBaselineReceipt? {
        if (deadline.hasPassedNow()) return null
        currentCoroutineContext().ensureActive()
        return probe.run(Path.of(executable), deadline)
    }

    private data class Selection(
        val artifact: ClaudeArtifactFacts,
        val baseline: ClaudeBaselineReceipt?,
        val isProvenanceConfirmed: Boolean?,
        val executable: String?,
    )
}

/**
 * One-use owner of the restricted baseline and the exact artifact identity it checked. The probe process was
 * already joined before this candidate was returned, so [close] releases no process of its own. After the ordinary
 * runtime starts, promotion verifies its observed version, selected executable and the still-matching executable
 * digest against the evidence; a mismatch closes that runtime and transfers nothing.
 */
internal class ClaudeRuntimeCandidate(
    override val request: EngineRuntimeCandidateRequest,
    override val evidence: EngineCompatibilityEvidence,
    private val selectedExecutable: String?,
    private val dispatchers: DispatcherProvider,
    private val promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
) : EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)
    private val log = Log.tag("ClaudeRuntimeCandidate")

    override suspend fun promote(expected: EngineCompatibilityStamp, deadline: TimeMark): EngineRuntimePromotion {
        currentCoroutineContext().ensureActive()
        if (deadline.hasPassedNow()) fail(EngineFailure.Transport(TransportFailureReason.Timeout))
        if (!consumed.compareAndSet(false, true)) {
            fail(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        // Nothing may be transferred while the assessed contract is inconclusive or was rejected.
        val assessed = assessedStamp()
        if (assessed == null || evidence.isBaselineConfirmed != true || evidence.capabilities == null) {
            fail(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
        }
        if (assessed != expected) fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        val runtime = promote(expected)
        var isTransferred = false
        try {
            currentCoroutineContext().ensureActive()
            verifyPromoted(runtime, expected)
            isTransferred = true
            return EngineRuntimePromotion(runtime, expected)
        } finally {
            // A runtime that does not prove the assessed artifact never becomes the facade's runtime.
            if (!isTransferred) withContext(NonCancellable) { closeTransferred(runtime) }
        }
    }

    private suspend fun verifyPromoted(runtime: EngineRuntime, stamp: EngineCompatibilityStamp) {
        val observed = runtime as? ClaudeAdmittedRuntime
        val executable = selectedExecutable
        val isSelected = executable != null && observed?.selectedExecutable == executable
        val isVersion = observed?.observedVersion == stamp.runtime
        val isDigest = executable != null && isSelected &&
            claudeExecutableSha256(executable, dispatchers.io) == stamp.runtimeFingerprint
        val isSameRuntime = runtime.identity == request.identity && runtime.compatibilityStamp == stamp
        if (!isSameRuntime) rejectPromotion()
        if (!isVersion || !isSelected || !isDigest) rejectPromotion()
    }

    private fun rejectPromotion(): Nothing {
        log.w { "Claude promotion no longer matches the assessed evidence" }
        fail(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
    }

    private suspend fun closeTransferred(runtime: EngineRuntime) {
        try {
            runtime.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Rejected Claude runtime could not be closed" }
        }
    }

    /** The record the facade assessed from this evidence, or null while an identity dimension is unknown. */
    private fun assessedStamp(): EngineCompatibilityStamp? {
        val identity = evidence.identity
        val facts = listOf(
            identity.engine, identity.heartbeat, identity.connector, identity.runtime, identity.protocol,
            identity.configurationSchema, identity.historySchema, identity.nativeTarget, identity.runtimeFingerprint,
        )
        if (facts.any { it == null || (it is String && it.isBlank()) }) return null
        return EngineCompatibilityStamp(
            engine = requireNotNull(identity.engine),
            heartbeat = requireNotNull(identity.heartbeat),
            connector = requireNotNull(identity.connector),
            runtime = requireNotNull(identity.runtime),
            protocol = requireNotNull(identity.protocol),
            configurationSchema = requireNotNull(identity.configurationSchema),
            historySchema = requireNotNull(identity.historySchema),
            nativeTarget = requireNotNull(identity.nativeTarget),
            runtimeFingerprint = requireNotNull(identity.runtimeFingerprint),
        )
    }

    override suspend fun close() {
        consumed.set(true)
    }
}

private fun fail(failure: EngineFailure): Nothing = throw EngineException(failure)
