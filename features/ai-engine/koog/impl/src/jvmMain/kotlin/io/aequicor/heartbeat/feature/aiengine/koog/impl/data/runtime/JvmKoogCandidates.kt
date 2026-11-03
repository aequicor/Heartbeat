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
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/** Restricted, non-promoting candidate until the full behavioral baseline and loaded-classpath proofs exist. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmKoogCandidates(
    offline: OfflineHttpClients,
    platform: PlatformInfo,
    dispatchers: DispatcherProvider,
) : KoogCandidates {
    private val probe = KoogSdkProbe(offline, platform, dispatchers)
    private val nativeProbe = KoogNativeProbe(dispatchers)

    override suspend fun open(request: EngineRuntimeCandidateRequest, deadline: TimeMark): EngineRuntimeCandidate? {
        currentCoroutineContext().ensureActive()
        // Do not substitute the embedded SDK for an explicitly different installation/configuration contract.
        val provider = KoogProvider.entries.firstOrNull { it.id == request.provider }
        if (provider == null || request.identity.engine != KoogEngineId || request.launch != LaunchContext()) {
            return null
        }
        val report = probe.run(deadline, listOf(provider))
        currentCoroutineContext().ensureActive()
        val native = nativeProbe.run(deadline, provider)
        currentCoroutineContext().ensureActive()
        return PartialKoogCandidate(request, report, native)
    }
}

internal class PartialKoogCandidate(
    override val request: EngineRuntimeCandidateRequest,
    report: KoogSdkReport,
    val native: KoogNativeReceipt? = null,
) : EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)
    private val hasRejectedProviderChecks = report.receipts.any {
        it.provider.id == request.provider && it.outcome == KoogSdkOutcome.Rejected
    }
    private val hasRejectedNativeChecks = native != null &&
        (
            !native.isCreateAndSendConfirmed || !native.isHistoryAndResumeConfirmed ||
                !native.isCancellationConfirmed || !native.isRecoveryConfirmed
        )
    override val evidence = EngineCompatibilityEvidence(
        identity = EngineCompatibilityIdentity(engine = KoogEngineId),
        // A conclusive SDK failure is a failed prerequisite. Success does not prove the whole baseline.
        isBaselineConfirmed = if (hasRejectedProviderChecks || hasRejectedNativeChecks) false else null,
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
