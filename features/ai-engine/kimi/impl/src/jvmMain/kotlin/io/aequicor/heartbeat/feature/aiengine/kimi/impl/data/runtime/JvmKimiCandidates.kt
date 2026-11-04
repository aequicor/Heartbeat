package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KIMI_RUNTIME_VERSION
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiCompatibilityProbe
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiControlledRuntime
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiControlledSelection
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiRuntimeSetup
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.isKnownKimiRelease
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.kimiExecutableSha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/**
 * Restricted candidate of the desktop Kimi adapter. Acquisition resolves exactly the selected controlled copy
 * through the adapter's own install precedence, verifies the pinned release digest (and the store's recorded
 * executable digest when present), and then runs only the existing isolated ACP `initialize` inspect: temporary
 * home/work, cleared environment, no login, no `session/new`, no model prompt, no history write. It never
 * substitutes a retained, bundled, custom or system copy, and a copy without the pin stays Unknown and is refused.
 * Promotion hands over the ordinary runtime the adapter builds for the assessed stamp and is allowed at most once.
 */
@ContributesBinding(ProfileScope::class)
internal class JvmKimiCandidates internal constructor(
    private val setup: KimiRuntimeSetup,
    private val platform: PlatformInfo,
    private val dispatchers: DispatcherProvider,
    private val probe: KimiBaselineProbe,
    private val isTrusted: (KimiControlledRuntime) -> Boolean,
) : KimiCandidates {
    @Inject
    constructor(
        setup: KimiRuntimeSetup,
        platform: PlatformInfo,
        dispatchers: DispatcherProvider,
        probe: KimiCompatibilityProbe,
    ) : this(setup, platform, dispatchers, probe, ::isKnownKimiRelease)

    private val log = Log.tag("KimiCandidates")

    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (String?, EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? {
        currentCoroutineContext().ensureActive()
        val selected = selected(request, deadline) ?: return null
        val evidence = KimiJvmCompatibility.evidence(
            selected.facts,
            selected.baseline,
            selected.isProvenanceConfirmed,
        )
        return KimiRuntimeCandidate(request, evidence, selected.executable, dispatchers, promote)
    }

    /**
     * The exact selected source, never a substitution. Null means this platform or a staged request without its
     * record cannot be admitted at all; every other refusal is an unknown candidate that promotion rejects.
     */
    private suspend fun selected(request: EngineRuntimeCandidateRequest, deadline: TimeMark): Selection? {
        val isUnsupported = request.identity.engine != KimiEngine.Id ||
            platform.host.name !in DESKTOP_HOSTS ||
            (request.launch.isInstallCandidate && request.launch.managed == null)
        return when {
            isUnsupported -> null

            // The adapter never runs `LaunchSettings.executable`, so a custom path would be a silent substitution.
            !request.launch.settings.executable.isNullOrBlank() -> unknownSelection()

            else -> resolve(request, deadline)
        }
    }

    private suspend fun resolve(request: EngineRuntimeCandidateRequest, deadline: TimeMark): Selection = try {
        selection(request, deadline)
    } catch (error: EngineException) {
        log.w(error) { "Selected Kimi runtime is not a pinned controlled copy" }
        unknownSelection()
    } catch (error: IOException) {
        log.w(IllegalStateException(error.javaClass.simpleName)) { "Selected Kimi runtime could not be read" }
        unknownSelection()
    } catch (error: SecurityException) {
        log.w(IllegalStateException(error.javaClass.simpleName)) { "Selected Kimi runtime was denied" }
        unknownSelection()
    }

    private suspend fun selection(request: EngineRuntimeCandidateRequest, deadline: TimeMark): Selection {
        // The staged/managed/bundled precedence, hashing and manifest pin stay in the adapter's own preflight.
        val controlled: KimiControlledSelection = setup.locateSelection(request.launch)
        val runtime = controlled.runtime
        val recorded = controlled.managed?.executableSha256
        val isRecordedMatch = recorded == null || recorded.equals(runtime.fingerprint, ignoreCase = true)
        return when {
            // The published pin for this target is the only trust anchor; never trust the adjacent manifest alone.
            !isTrusted(runtime) -> Selection(
                KimiArtifactFacts(runtime = null, nativeTarget = runtime.target, runtimeFingerprint = null),
                baseline = null,
                isProvenanceConfirmed = null,
                executable = runtime.executable,
            )

            // Only the selected managed record may add a second anchor; a bundled copy has no store record by design.
            !isRecordedMatch -> Selection(
                KimiArtifactFacts(runtime = null, nativeTarget = runtime.target, runtimeFingerprint = null),
                baseline = null,
                isProvenanceConfirmed = false,
                executable = runtime.executable,
            )

            else -> {
                val baseline = baseline(runtime.executable, controlled.source, request.launch, deadline)
                Selection(
                    KimiArtifactFacts(
                        runtime = baseline?.runtimeVersion,
                        nativeTarget = runtime.target,
                        runtimeFingerprint = runtime.fingerprint,
                    ),
                    baseline,
                    isProvenanceConfirmed = true,
                    executable = runtime.executable,
                )
            }
        }
    }

    private fun unknownSelection(): Selection =
        Selection(unknownFacts(), baseline = null, isProvenanceConfirmed = null, executable = null)

    private suspend fun baseline(
        executable: String,
        source: InstallSource,
        launch: LaunchContext,
        deadline: TimeMark,
    ): KimiBaselineReceipt? {
        if (deadline.hasPassedNow()) return null
        currentCoroutineContext().ensureActive()
        val remaining = (-deadline.elapsedNow()).inWholeMilliseconds.coerceIn(1L, BASELINE_TIMEOUT_MILLIS)
        return withTimeoutOrNull(remaining) {
            probe.run(Installation(source, KIMI_RUNTIME_VERSION, executable), launch)
        }
    }

    private fun unknownFacts(): KimiArtifactFacts =
        KimiArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null)

    private data class Selection(
        val facts: KimiArtifactFacts,
        val baseline: KimiBaselineReceipt?,
        val isProvenanceConfirmed: Boolean?,
        val executable: String?,
    )

    private companion object {
        val DESKTOP_HOSTS = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)
        const val BASELINE_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * One-use owner of the restricted baseline and the exact artifact identity it checked. The inspect process was
 * already joined before this candidate was returned, so [close] releases no process of its own. The promotion
 * callback receives the verified executable so the adapter can compare it with the controlled copy it is about to
 * start before that process opens; after it starts, promotion re-verifies the observed runtime version, the
 * negotiated ACP identity, the selected executable and its still-matching digest against the evidence; a mismatch
 * closes that runtime and transfers nothing. It never creates or resumes a native session.
 */
internal class KimiRuntimeCandidate(
    override val request: EngineRuntimeCandidateRequest,
    override val evidence: EngineCompatibilityEvidence,
    private val selectedExecutable: String?,
    private val dispatchers: DispatcherProvider,
    private val promote: suspend (String?, EngineCompatibilityStamp) -> EngineRuntime,
) : EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)
    private val log = Log.tag("KimiRuntimeCandidate")

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
        val observed = runtime as? KimiAdmittedRuntime
        val executable = selectedExecutable
        val isSelected = executable != null && observed?.selectedExecutable == executable
        val isVersion = observed?.observedRuntimeVersion == stamp.runtime
        val isProtocol = observed?.observedProtocol == stamp.protocol
        val isDigest = executable != null && isSelected && digest(executable) == stamp.runtimeFingerprint
        val isSameRuntime = runtime.identity == request.identity && runtime.compatibilityStamp == stamp
        val isObserved = isSelected && isVersion && isProtocol && isDigest
        if (!isSameRuntime || !isObserved) rejectPromotion()
    }

    private suspend fun digest(executable: String): String? = withContext(dispatchers.io) {
        val job = currentCoroutineContext()[Job]
        try {
            kimiExecutableSha256(File(executable)) { job?.ensureActive() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            log.w(IllegalStateException(error.javaClass.simpleName)) { "Promoted Kimi digest is unavailable" }
            null
        } catch (error: SecurityException) {
            log.w(IllegalStateException(error.javaClass.simpleName)) { "Promoted Kimi digest was denied" }
            null
        }
    }

    private fun rejectPromotion(): Nothing {
        log.w { "Kimi promotion no longer matches the assessed evidence" }
        fail(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
    }

    private suspend fun closeTransferred(runtime: EngineRuntime) {
        try {
            runtime.close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Rejected Kimi runtime could not be closed" }
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
