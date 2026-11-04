package io.aequicor.heartbeat.feature.aiengine.codex.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexHost
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexTarget
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.codexExecutableSha256
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.codexReleaseTarget
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.fail
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.resolveCodexLaunch
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.verifyCodexManagedCopy
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
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
 * Restricted candidate of the desktop Codex adapter. Acquisition verifies the exact selected executable against
 * the managed copy's recorded executable digest and then runs only the clean-home app-server baseline; it reads no
 * credentials, user home, project configuration, tools or history and starts no thread, turn or model request.
 * Promotion hands over the ordinary runtime the adapter builds for the assessed stamp and is allowed at most once.
 */
@ContributesBinding(ProfileScope::class)
internal class JvmCodexCandidates internal constructor(
    private val config: CodexLocalConfiguration,
    private val platform: PlatformInfo,
    private val host: CodexHost,
    private val dispatchers: DispatcherProvider,
    private val probe: CodexBaselineProbe,
) : CodexCandidates {
    @Inject
    constructor(
        config: CodexLocalConfiguration,
        platform: PlatformInfo,
        dispatchers: DispatcherProvider,
    ) : this(config, platform, CodexHost(), dispatchers, JvmCodexBaselineProbe(dispatchers))

    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (String?, EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? {
        currentCoroutineContext().ensureActive()
        val target = desktopTarget(request) ?: return null
        return selection(request, target, deadline)?.let { selected ->
            CodexRuntimeCandidate(
                request,
                CodexJvmCompatibility.evidence(selected.artifact, selected.baseline, selected.isProvenanceConfirmed),
                selected.executable,
                dispatchers,
                promote,
            )
        }
    }

    private fun desktopTarget(request: EngineRuntimeCandidateRequest): CodexTarget? {
        if (request.identity.engine != CodexEngine.Id || platform.host.name !in DESKTOP_HOSTS) return null
        return codexReleaseTarget(host)
    }

    /**
     * A staged install candidate is inspected exactly as supplied; no retained, bundled or system copy is
     * substituted. Otherwise the selected launch source decides: only a managed copy with a recorded executable
     * digest can establish provenance. A custom or system executable has no pin and stays unobservable.
     */
    private suspend fun selection(
        request: EngineRuntimeCandidateRequest,
        target: CodexTarget,
        deadline: TimeMark,
    ): Selection? {
        if (request.launch.isInstallCandidate) {
            val managed = request.launch.managed ?: return null
            return managed(managed, target, deadline)
        }
        val launch = resolveCodexLaunch(request.launch, config, host)
        return when (launch.source) {
            InstallSource.Managed -> {
                val managed = request.launch.managed?.takeIf { it.executable == launch.executable }
                if (managed == null) unknown() else managed(managed, target, deadline)
            }

            InstallSource.Custom,
            InstallSource.System,
            -> unknown()

            InstallSource.Bundled,
            InstallSource.HostApp,
            InstallSource.BuiltIn,
            InstallSource.Missing,
            -> null
        }
    }

    private suspend fun managed(managed: ManagedInstall, target: CodexTarget, deadline: TimeMark): Selection {
        val isVerified = verifyCodexManagedCopy(managed, dispatchers.io)
        if (isVerified != true) {
            // Without a matching recorded executable digest nothing about the artifact is established.
            return Selection(
                CodexArtifactFacts(runtime = null, nativeTarget = target.triple, runtimeFingerprint = null),
                baseline = null,
                isProvenanceConfirmed = isVerified,
                executable = managed.executable,
            )
        }
        val baseline = baseline(managed.executable, deadline)
        return Selection(
            CodexArtifactFacts(
                runtime = baseline?.version,
                nativeTarget = target.triple,
                runtimeFingerprint = managed.executableSha256,
            ),
            baseline,
            isProvenanceConfirmed = true,
            executable = managed.executable,
        )
    }

    private fun unknown(): Selection = Selection(
        CodexArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null),
        baseline = null,
        isProvenanceConfirmed = null,
        executable = null,
    )

    private suspend fun baseline(executable: String, deadline: TimeMark): CodexBaselineReceipt? {
        if (deadline.hasPassedNow()) return null
        currentCoroutineContext().ensureActive()
        return probe.run(Path.of(executable), deadline)
    }

    private data class Selection(
        val artifact: CodexArtifactFacts,
        val baseline: CodexBaselineReceipt?,
        val isProvenanceConfirmed: Boolean?,
        val executable: String?,
    )

    private companion object {
        val DESKTOP_HOSTS = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)
    }
}

/**
 * One-use owner of the restricted baseline and the exact artifact identity it checked. The probe process was
 * already joined before this candidate was returned, so [close] releases no process of its own. The promotion
 * callback receives the verified executable so the adapter can compare it with the captured launch before that
 * process opens; after it starts, promotion again verifies its handshake version and executable digest against the
 * evidence; a mismatch closes that runtime and transfers nothing.
 */
internal class CodexRuntimeCandidate(
    override val request: EngineRuntimeCandidateRequest,
    override val evidence: EngineCompatibilityEvidence,
    private val selectedExecutable: String?,
    private val dispatchers: DispatcherProvider,
    private val promote: suspend (String?, EngineCompatibilityStamp) -> EngineRuntime,
) : EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)
    private val log = Log.tag("CodexRuntimeCandidate")

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
        val runtime = promote(selectedExecutable, expected)
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
        val observed = runtime as? CodexAdmittedRuntime
        val executable = selectedExecutable
        val isSelected = executable != null && observed?.selectedExecutable == executable
        val isVersion = observed?.handshakeVersion == stamp.runtime
        val isDigest = isSelected &&
            codexExecutableSha256(executable, dispatchers.io) == stamp.runtimeFingerprint
        val isSameRuntime = runtime.identity == request.identity && runtime.compatibilityStamp == stamp
        if (!isSameRuntime) rejectPromotion()
        if (!isVersion || !isSelected || !isDigest) rejectPromotion()
    }

    private fun rejectPromotion(): Nothing {
        log.w { "Codex promotion no longer matches the assessed evidence" }
        fail(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
    }

    private suspend fun closeTransferred(runtime: EngineRuntime) {
        try {
            runtime.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Rejected Codex runtime could not be closed" }
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
