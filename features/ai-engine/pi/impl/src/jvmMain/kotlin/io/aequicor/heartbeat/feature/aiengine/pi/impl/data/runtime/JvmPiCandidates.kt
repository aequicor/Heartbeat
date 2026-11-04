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
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeDistribution
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeRelease
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.piFailure
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.resolvePiStartup
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.verifyPiManagedCopy
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.withoutDetails
import kotlinx.coroutines.CancellationException
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
        promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? {
        currentCoroutineContext().ensureActive()
        val selected = selection(request, deadline) ?: return null
        return PiRuntimeCandidate(
            request,
            PiJvmCompatibility.evidence(selected.artifact, selected.baseline, selected.isProvenanceConfirmed),
            promote,
        )
    }

    private suspend fun selection(request: EngineRuntimeCandidateRequest, deadline: TimeMark): Selection? {
        val release = distribution.release?.takeIf {
            request.identity.engine == PiEngineId && platform.host.name in PiJvmCompatibility.desktopTargets
        } ?: return null
        // A staged install candidate is inspected exactly as supplied; never substitute a retained or bundled copy.
        val selected = if (request.launch.isInstallCandidate) {
            request.launch.managed?.let { managed(request, release, deadline) }
        } else {
            val startup = resolvePiStartup(request.launch, distribution.executable(), release.version)
            when (startup.source) {
                InstallSource.Managed -> managed(request, release, deadline)

                InstallSource.Bundled -> bundled(release, deadline)

                InstallSource.Custom -> Selection(
                    PiArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null),
                    baseline = null,
                    isProvenanceConfirmed = null,
                )

                InstallSource.BuiltIn,
                InstallSource.System,
                InstallSource.HostApp,
                InstallSource.Missing,
                -> null
            }
        }
        return selected
    }

    private suspend fun managed(
        request: EngineRuntimeCandidateRequest,
        release: PiRuntimeRelease,
        deadline: TimeMark,
    ): Selection {
        val managed = request.launch.managed
        val isVerified = managed?.let { verifyPiManagedCopy(it, dispatchers.io) }
        val artifact = PiArtifactFacts(
            runtime = managed?.version,
            nativeTarget = release.target,
            // The executable digest the store recorded, never the release archive digest.
            runtimeFingerprint = managed?.executableSha256?.takeIf { isVerified == true },
        )
        if (managed == null || isVerified != true) {
            return Selection(artifact, baseline = null, isProvenanceConfirmed = isVerified)
        }
        return Selection(artifact, baseline = baseline(managed.executable, deadline), isProvenanceConfirmed = true)
    }

    private suspend fun bundled(release: PiRuntimeRelease, deadline: TimeMark): Selection {
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
            )

            is Prepared.Failed -> Selection(
                artifact,
                baseline = null,
                isProvenanceConfirmed = prepared.isProvenanceConfirmed,
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
    private val promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
) : EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)

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
        val runtime = promote(expected)
        currentCoroutineContext().ensureActive()
        return EngineRuntimePromotion(runtime, expected)
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
