package io.aequicor.heartbeat.feature.aiengine.pi.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
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
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeAdmission
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeDistribution
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeRelease
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.managedPiStartup
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.piFailure
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.pinnedPiStartup
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.resolvePiStartup
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.verifyPiManagedCopy
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.withoutDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/**
 * Restricted candidate of the desktop Pi adapter. Acquisition verifies the exact selected executable against its
 * pinned digest and then runs only the socket-free offline catalog baseline; it reads no credentials, user home,
 * project configuration, tools or history and sends no model request. Promotion hands over the ordinary runtime the
 * adapter builds for the assessed stamp and is allowed at most once.
 */
@ContributesBinding(ProfileScope::class)
internal class JvmPiCandidates internal constructor(
    private val distribution: PiRuntimeDistribution,
    private val platform: PlatformInfo,
    private val dispatchers: DispatcherProvider,
    private val probe: PiBaselineProbe,
) : PiCandidates {
    @Inject
    constructor(
        distribution: PiRuntimeDistribution,
        platform: PlatformInfo,
        dispatchers: DispatcherProvider,
    ) : this(distribution, platform, dispatchers, JvmPiBaselineProbe(dispatchers))

    private val log = Log.tag("PiCandidates")

    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (PiRuntimeAdmission?, EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? {
        currentCoroutineContext().ensureActive()
        val selected = selection(request, deadline) ?: return null
        return PiRuntimeCandidate(
            request,
            PiJvmCompatibility.evidence(selected.artifact, selected.baseline, selected.isProvenanceConfirmed),
            selected.admission,
            promote,
        )
    }

    private suspend fun selection(request: EngineRuntimeCandidateRequest, deadline: TimeMark): Selection? {
        val release = distribution.release?.takeIf {
            request.identity.engine == PiEngineId && platform.host.name in PiJvmCompatibility.desktopTargets
        } ?: return null
        // A staged install candidate is inspected exactly as supplied; never substitute a retained or bundled copy.
        if (request.launch.isInstallCandidate) {
            val staged = request.launch.managed ?: return null
            return managed(request, staged, release, deadline)
        }
        return when (resolvePiStartup(request.launch, distribution.executable(), release.version).source) {
            InstallSource.Managed -> managed(request, request.launch.managed, release, deadline)

            InstallSource.Bundled -> bundled(request, release, deadline)

            InstallSource.Custom -> Selection(
                PiArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null),
                baseline = null,
                isProvenanceConfirmed = null,
                admission = null,
            )

            InstallSource.BuiltIn,
            InstallSource.System,
            InstallSource.HostApp,
            InstallSource.Missing,
            -> null
        }
    }

    private suspend fun managed(
        request: EngineRuntimeCandidateRequest,
        managed: ManagedInstall?,
        release: PiRuntimeRelease,
        deadline: TimeMark,
    ): Selection {
        // A selected managed source without its record has no provenance; it stays an inconclusive candidate.
        if (managed == null) {
            return Selection(
                PiArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null),
                baseline = null,
                isProvenanceConfirmed = null,
                admission = null,
            )
        }
        val isVerified = verifyPiManagedCopy(managed, dispatchers.io)
        val artifact = PiArtifactFacts(
            runtime = managed.version,
            nativeTarget = release.target,
            // The executable digest the store recorded, never the release archive digest.
            runtimeFingerprint = managed.executableSha256?.takeIf { isVerified == true },
        )
        if (isVerified != true) {
            return Selection(artifact, baseline = null, isProvenanceConfirmed = isVerified, admission = null)
        }
        // The exact bytes verified above are the startup every later process of this runtime must use.
        val startup = managedPiStartup(request.launch, managed)
            ?: return Selection(artifact, baseline = null, isProvenanceConfirmed = false, admission = null)
        val executable = checkNotNull(startup.executable)
        return Selection(
            artifact,
            baseline = baseline(executable.toString(), deadline),
            isProvenanceConfirmed = true,
            admission = PiRuntimeAdmission(startup, executable, managed),
        )
    }

    private suspend fun bundled(
        request: EngineRuntimeCandidateRequest,
        release: PiRuntimeRelease,
        deadline: TimeMark,
    ): Selection {
        val prepared = try {
            Prepared.Ready(withContext(dispatchers.io) { distribution.prepare {} })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReleaseException) {
            log.w(e.withoutDetails()) { "Pinned Pi runtime could not be verified" }
            Prepared.Failed(e.failure in PROVENANCE_FAILURES)
        } catch (e: IllegalArgumentException) {
            log.w(e.withoutDetails()) { "Pinned Pi runtime inventory differs" }
            Prepared.Failed(false)
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "Pinned Pi runtime is unavailable" }
            Prepared.Failed(null)
        } catch (e: SecurityException) {
            log.w(e.withoutDetails()) { "Pinned Pi runtime is unavailable" }
            Prepared.Failed(null)
        }
        val artifact = PiArtifactFacts(
            runtime = release.version,
            nativeTarget = release.target,
            runtimeFingerprint = (prepared as? Prepared.Ready)?.let { release.artifact.download.sha256 },
        )
        return when (prepared) {
            is Prepared.Ready -> Selection(
                artifact,
                baseline = baseline(prepared.executable.toString(), deadline),
                isProvenanceConfirmed = true,
                admission = PiRuntimeAdmission(
                    pinnedPiStartup(request.launch, prepared.executable, InstallSource.Bundled, release.version),
                    prepared.executable,
                    managed = null,
                ),
            )

            is Prepared.Failed -> Selection(
                artifact,
                baseline = null,
                isProvenanceConfirmed = prepared.isProvenanceConfirmed,
                admission = null,
            )
        }
    }

    private suspend fun baseline(executable: String, deadline: TimeMark): PiBaselineReceipt? {
        if (deadline.hasPassedNow()) return null
        currentCoroutineContext().ensureActive()
        return probe.run(Path.of(executable), deadline)
    }
}

/**
 * One-use owner of the restricted baseline and the exact artifact identity it checked. The probe process was
 * already joined before this candidate was returned, so [close] releases no process of its own.
 */
internal class PiRuntimeCandidate(
    override val request: EngineRuntimeCandidateRequest,
    override val evidence: EngineCompatibilityEvidence,
    private val admission: PiRuntimeAdmission?,
    private val promote: suspend (PiRuntimeAdmission?, EngineCompatibilityStamp) -> EngineRuntime,
) : EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)
    private val log = Log.tag("PiRuntimeCandidate")

    override suspend fun promote(expected: EngineCompatibilityStamp, deadline: TimeMark): EngineRuntimePromotion {
        currentCoroutineContext().ensureActive()
        if (deadline.hasPassedNow()) piFailure(EngineFailure.Transport(TransportFailureReason.Timeout))
        if (!consumed.compareAndSet(false, true)) {
            piFailure(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        // Nothing may be transferred while the assessed contract is inconclusive or was rejected.
        val assessed = assessedStamp()
        if (assessed == null || evidence.isBaselineConfirmed != true || evidence.capabilities == null) {
            piFailure(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
        }
        if (assessed != expected) piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        val runtime = promote(admission, expected)
        var isTransferred = false
        try {
            currentCoroutineContext().ensureActive()
            verifyPromoted(runtime, expected)
            isTransferred = true
            return EngineRuntimePromotion(runtime, expected)
        } finally {
            // A runtime that does not prove the admitted artifact never becomes the facade's runtime.
            if (!isTransferred) withContext(NonCancellable) { closeTransferred(runtime) }
        }
    }

    /** The admitted executable and version are the ones the runtime reports it was built to start. */
    private fun verifyPromoted(runtime: EngineRuntime, stamp: EngineCompatibilityStamp) {
        val admitted = runtime as? PiAdmittedRuntime
        val pin = admission
        val isSameRuntime = runtime.identity == request.identity && runtime.compatibilityStamp == stamp
        val isSelected = pin != null && admitted?.selectedExecutable == pin.executable
        val isVersion = pin != null && admitted?.admittedVersion == stamp.runtime
        if (!isSameRuntime || !isSelected || !isVersion) rejectPromotion()
    }

    private fun rejectPromotion(): Nothing {
        log.w { "Pi promotion no longer matches the admitted artifact" }
        piFailure(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
    }

    private suspend fun closeTransferred(runtime: EngineRuntime) {
        try {
            runtime.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Rejected Pi runtime could not be closed" }
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

private data class Selection(
    val artifact: PiArtifactFacts,
    val baseline: PiBaselineReceipt?,
    val isProvenanceConfirmed: Boolean?,
    val admission: PiRuntimeAdmission?,
)

private sealed interface Prepared {
    data class Ready(val executable: Path) : Prepared

    data class Failed(val isProvenanceConfirmed: Boolean?) : Prepared
}

/** Release failures that prove the artifact differs from the pinned bytes, as opposed to an unobserved download. */
private val PROVENANCE_FAILURES = setOf(
    ReleaseFailure.InvalidMetadata,
    ReleaseFailure.UntrustedSource,
    ReleaseFailure.TooLarge,
    ReleaseFailure.SizeMismatch,
    ReleaseFailure.ChecksumMismatch,
)
