package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/** Restricted, non-promoting candidate until native-session and loaded-classpath proofs have been implemented. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmKoogCandidates(
    offline: OfflineHttpClients,
    platform: PlatformInfo,
    dispatchers: DispatcherProvider,
) : KoogCandidates {
    private val probe = KoogSdkProbe(offline, platform, dispatchers)

    override suspend fun open(request: EngineRuntimeCandidateRequest, deadline: TimeMark): EngineRuntimeCandidate? {
        currentCoroutineContext().ensureActive()
        // Do not substitute the embedded SDK for an explicitly different installation/configuration contract.
        if (request.identity.engine != KoogEngineId || request.launch != LaunchContext()) return null
        val report = probe.run(deadline)
        currentCoroutineContext().ensureActive()
        return SdkOnlyKoogCandidate(request, report)
    }
}

internal class SdkOnlyKoogCandidate(override val request: EngineRuntimeCandidateRequest, report: KoogSdkReport) :
    EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)
    override val evidence = EngineCompatibilityEvidence(
        identity = EngineCompatibilityIdentity(engine = KoogEngineId),
        // A conclusive SDK failure is a failed prerequisite. Success does not prove the whole baseline.
        isBaselineConfirmed = if (report.hasRejectedChecks) false else null,
    )

    override suspend fun promote(expected: EngineCompatibilityStamp, deadline: TimeMark): EngineRuntimePromotion {
        currentCoroutineContext().ensureActive()
        if (!consumed.compareAndSet(false, true)) {
            fail(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        fail(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
    }

    override suspend fun close() {
        // Every probe resource was already joined before acquisition returned; there is no ordinary runtime.
        consumed.set(true)
    }
}
