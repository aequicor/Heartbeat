package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DeepSeekHost
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshExternalFingerprint
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshProcessObservation
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.resolveDeepSeekStartup
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/**
 * Restricted candidate of the desktop DSH adapter. DSH is an external runtime, so acquisition resolves exactly the
 * adapter's own `dsh` launch (never a staged, managed or substituted copy), hashes the selected launcher into an
 * explicitly marked `external:sha256:` fingerprint and then runs only the shared ACP `initialize` inspect: isolated
 * temporary home/agents/work/temp, cleared environment, no login, no `session/new`, no prompt, no history write.
 * Missing, non-runnable or unreadable executables stay Unknown and are refused. Promotion hands over the ordinary
 * grouped DSH runtime for the assessed stamp and is allowed at most once.
 */
@ContributesBinding(ProfileScope::class)
internal class JvmDshCandidates internal constructor(
    private val configuration: DeepSeekConfiguration,
    private val platform: PlatformInfo,
    private val dispatchers: DispatcherProvider,
    private val probe: DshBaselineProbe,
    private val versions: DshRuntimeVersionProbe,
    private val host: DeepSeekHost,
) : DshCandidates {
    @Inject
    constructor(
        platform: PlatformInfo,
        dispatchers: DispatcherProvider,
        probe: DshCompatibilityProbe,
        versions: JvmDshRuntimeVersionProbe,
    ) : this(DeepSeekConfiguration(), platform, dispatchers, probe, versions, DeepSeekHost())

    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? {
        currentCoroutineContext().ensureActive()
        val selected = selection(request, deadline) ?: return null
        return DshRuntimeCandidate(
            request,
            DshJvmCompatibility.evidence(selected.artifact, selected.baseline, selected.isProvenanceConfirmed),
            selected.executable,
            dispatchers,
            versions,
            promote,
        )
    }

    /**
     * The exact selected source, never a substitution. Null means this platform or a staged install request cannot
     * be admitted at all; every other refusal is an unknown candidate that promotion rejects.
     */
    private suspend fun selection(request: EngineRuntimeCandidateRequest, deadline: TimeMark): Selection? {
        val isSupported = request.identity.engine == DeepSeekEngine.Id &&
            platform.host.name in DESKTOP_HOSTS &&
            !request.launch.isInstallCandidate
        if (!isSupported) return null

        val target = dshNativeTarget(host) ?: return unknown()
        val startup = withContext(dispatchers.io) { resolveDeepSeekStartup(configuration, host) }
        if (startup.source !in ACCEPTED_SOURCES || !startup.isRunnable) return unknown()
        val fingerprint = fingerprint(startup.executable) ?: return unknown()
        val baseline = baseline(
            Installation(startup.source, version = null, path = startup.executable, isRunnable = true),
            deadline,
        )
        return Selection(
            DshArtifactFacts(
                runtime = baseline?.runtimeVersion,
                nativeTarget = target,
                runtimeFingerprint = fingerprint,
            ),
            baseline,
            isProvenanceConfirmed = true,
            executable = startup.executable,
        )
    }

    private fun unknown(): Selection =
        Selection(unknownFacts(), baseline = null, isProvenanceConfirmed = null, executable = null)

    private fun unknownFacts(): DshArtifactFacts =
        DshArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null)

    private suspend fun fingerprint(executable: String): String? = withContext(dispatchers.io) {
        val job = currentCoroutineContext()[Job]
        DshExternalFingerprint.of(executable) { job?.ensureActive() }
    }

    private suspend fun baseline(installation: Installation, deadline: TimeMark): DshBaselineReceipt? {
        if (deadline.hasPassedNow()) return null
        currentCoroutineContext().ensureActive()
        val remaining = (-deadline.elapsedNow()).inWholeMilliseconds.coerceIn(1L, BASELINE_TIMEOUT_MILLIS)
        return withTimeoutOrNull(remaining) { probe.run(installation, deadline) }
            ?.takeUnless { it.initialize == DshBaselineOutcome.Unobserved }
    }

    private data class Selection(
        val artifact: DshArtifactFacts,
        val baseline: DshBaselineReceipt?,
        val isProvenanceConfirmed: Boolean?,
        val executable: String?,
    )

    private companion object {
        val DESKTOP_HOSTS = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)
        val ACCEPTED_SOURCES = setOf(InstallSource.Custom, InstallSource.System)
        const val BASELINE_TIMEOUT_MILLIS = 15_000L
    }
}

/**
 * One-use owner of the restricted baseline and the exact external artifact identity it checked. The inspect
 * process was already joined before this candidate was returned, so [close] releases no process of its own. After
 * the ordinary runtime starts, promotion re-observes the negotiated ACP identity and the selected launcher, reruns
 * the adapter's `--version` probe and recomputes the external fingerprint against the evidence; a mismatch closes
 * that runtime and transfers nothing. It never creates or resumes a native session.
 */
internal class DshRuntimeCandidate(
    override val request: EngineRuntimeCandidateRequest,
    override val evidence: EngineCompatibilityEvidence,
    private val selectedExecutable: String?,
    private val dispatchers: DispatcherProvider,
    private val versions: DshRuntimeVersionProbe,
    private val promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
) : EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)
    private val log = Log.tag("DshRuntimeCandidate")

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
            verifyPromoted(runtime, expected, deadline)
            isTransferred = true
            return EngineRuntimePromotion(runtime, expected)
        } finally {
            // A runtime that does not prove the assessed artifact never becomes the facade's runtime.
            if (!isTransferred) withContext(NonCancellable) { closeTransferred(runtime) }
        }
    }

    private suspend fun verifyPromoted(runtime: EngineRuntime, stamp: EngineCompatibilityStamp, deadline: TimeMark) {
        val admitted = runtime as? DshAdmittedRuntime ?: rejectPromotion()
        val executable = selectedExecutable ?: rejectPromotion()
        val isSelected = admitted.selectedExecutable == executable
        val isSameRuntime = runtime.identity == request.identity && runtime.compatibilityStamp == stamp
        if (!isSameRuntime || !isSelected) rejectPromotion()
        val startup = observeStartup(admitted) ?: rejectPromotion()
        if (startup.selectedExecutable != executable || startup.protocol != stamp.protocol) rejectPromotion()
        val version = observeVersion(executable, deadline) ?: rejectPromotion()
        if (version != stamp.runtime) rejectPromotion()
        if (fingerprint(executable) != stamp.runtimeFingerprint) rejectPromotion()
    }

    private suspend fun observeStartup(runtime: DshAdmittedRuntime): DshProcessObservation? = try {
        runtime.observeOrdinaryStartup()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException(error.javaClass.simpleName)) { "Promoted DSH process did not report startup" }
        null
    }

    private suspend fun observeVersion(executable: String, deadline: TimeMark): String? = try {
        versions.observe(executable, deadline)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException(error.javaClass.simpleName)) { "Promoted DSH runtime line is unavailable" }
        null
    }

    private suspend fun fingerprint(executable: String): String? = withContext(dispatchers.io) {
        val job = currentCoroutineContext()[Job]
        DshExternalFingerprint.of(executable) { job?.ensureActive() }
    }

    private fun rejectPromotion(): Nothing {
        log.w { "DSH promotion no longer matches the assessed evidence" }
        fail(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
    }

    private suspend fun closeTransferred(runtime: EngineRuntime) {
        try {
            runtime.close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException(error.javaClass.simpleName)) { "Rejected DSH runtime could not be closed" }
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
