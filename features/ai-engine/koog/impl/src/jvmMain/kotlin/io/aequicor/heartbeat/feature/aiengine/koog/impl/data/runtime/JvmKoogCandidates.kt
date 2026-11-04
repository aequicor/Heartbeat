package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeMark

/**
 * Restricted candidate of the embedded JVM adapter. Acquisition runs only socket-free SDK and native-session
 * probes of the selected provider, with no credentials, user configuration, project, tools or history writes.
 * Promotion hands over the ordinary runtime the adapter builds for the assessed stamp and is allowed at most once.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmKoogCandidates(
    private val offline: OfflineHttpClients,
    private val platform: PlatformInfo,
    dispatchers: DispatcherProvider,
) : KoogCandidates {
    private val probe = KoogSdkProbe(offline, platform, dispatchers)
    private val nativeProbe = KoogNativeProbe(dispatchers)

    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? {
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
        val isWireConfirmed = nativeProbe.runProviderWire(deadline, offline, platform, provider) &&
            nativeProbe.runIncompleteWire(deadline, offline, platform, provider) &&
            nativeProbe.runMalformedWire(deadline, offline, platform, provider)
        currentCoroutineContext().ensureActive()
        return KoogRuntimeCandidate(
            request,
            KoogJvmCompatibility.evidence(platform.host, provider, report, native, isWireConfirmed),
            promote,
        )
    }
}

internal class KoogRuntimeCandidate(
    override val request: EngineRuntimeCandidateRequest,
    override val evidence: EngineCompatibilityEvidence,
    private val promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
) : EngineRuntimeCandidate {
    private val consumed = AtomicBoolean(false)
    private val log = Log.tag("KoogRuntimeCandidate")

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
            // A runtime that does not prove the admitted contract never becomes the facade's runtime.
            if (!isTransferred) withContext(NonCancellable) { closeTransferred(runtime) }
        }
    }

    /** The ordinary runtime must report the identity and assessed stamp the facade is about to admit. */
    private fun verifyPromoted(runtime: EngineRuntime, expected: EngineCompatibilityStamp) {
        val isSameRuntime = runtime.identity == request.identity && runtime.compatibilityStamp == expected
        if (!isSameRuntime || runtime.isClosed) {
            log.w { "Koog promotion no longer matches the assessed runtime" }
            fail(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
        }
    }

    private suspend fun closeTransferred(runtime: EngineRuntime) {
        try {
            runtime.close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The candidate no longer owns this runtime; the facade closes only what it received.
            log.w(error) { "Rejected Koog runtime could not be closed" }
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
        // Every probe resource was already joined before acquisition returned; there is no ordinary runtime.
        consumed.set(true)
    }
}
