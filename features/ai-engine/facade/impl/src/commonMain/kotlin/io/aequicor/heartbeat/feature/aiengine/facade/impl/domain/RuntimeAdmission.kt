package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeMark

/** Outcome of this attempt; an inspection report can never be substituted for [Accepted]. */
internal sealed interface RuntimeAdmissionResult {
    /** No researched policy or restricted acquisition implementation; no ordinary runtime was started. */
    data object Unavailable : RuntimeAdmissionResult

    /** The observed candidate was not permitted to access ordinary configuration or create sessions. */
    data class Rejected(val decision: EngineCompatibilityDecision) : RuntimeAdmissionResult

    /** Ownership of this exact runtime passes to the caller, which must pool or close it. */
    data class Accepted(
        val runtime: EngineRuntime,
        val decision: EngineCompatibilityDecision,
        val isNew: Boolean = true,
    ) : RuntimeAdmissionResult {
        init {
            require(decision.isBaselineAllowed)
        }

        val stamp: EngineCompatibilityStamp = requireNotNull(decision.stamp)

        override fun toString(): String = "RuntimeAdmissionResult.Accepted"
    }
}

/**
 * Two-phase acquisition, including a borrowed-runtime reuse branch. No legacy runtime method is called here.
 * Only new promotions belong to the orphan cleanup path; failed reuse never closes accepted pooled work.
 * Cleanup is awaited even past the
 * deadline; a caller requiring bounded UI wait must retain a separate worker owner until cleanup completes.
 */
internal class RuntimeAdmission(private val context: FacadeContext, private val cleanup: RuntimeAdmissionCleanup) {
    private val log = Log.tag("RuntimeAdmission")

    /**
     * Assesses one restricted candidate for [request] and always closes it. Unlike [acquire] nothing is
     * transferred, reused or prepared: only the observed evidence and the connector policy contribute to the
     * result, so a caller can report an admission decision before any ordinary runtime exists.
     */
    suspend fun assess(
        registration: EngineRegistration,
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        recheck: suspend () -> Unit,
    ): RouteAdmission {
        currentCoroutineContext().ensureActive()
        checkDeadline(deadline)
        requireProtocol(request.identity.engine == registration.descriptor.id)
        val factory = registration.factory.value
        val policy = factory.runtimeRequirements ?: return RouteAdmission.Unavailable
        requireProtocol(policy.engine == registration.descriptor.id)
        return withTimeout(-deadline.elapsedNow()) {
            var candidate: EngineRuntimeCandidate? = null
            try {
                recheck()
                checkDeadline(deadline)
                adapterCall(log, "open restricted candidate") {
                    // Capture inside the dispatcher boundary: prompt cancellation can drop its result.
                    withContext(context.io) { candidate = factory.openRuntimeCandidate(request, deadline) }
                }
                val acquired = candidate ?: return@withTimeout RouteAdmission.Unavailable
                requireProtocol(acquired.request == request)
                val decision = assessEngineCompatibility(policy, acquired.evidence)
                recheck()
                checkDeadline(deadline)
                RouteAdmission.Assessed(decision)
            } finally {
                val owned = candidate
                if (owned != null) withContext(NonCancellable) { closeCandidate(owned, request.identity) }
            }
        }
    }

    /**
     * Acquires one candidate and transfers it only while [recheck] still authorizes the canonical route and
     * the supplied launch snapshot. Rechecking must reject a changed source, launch or configuration contract.
     */
    suspend fun acquire(
        registration: EngineRegistration,
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        recheck: suspend () -> Unit,
        reuseOrRetire: suspend (EngineCompatibilityDecision) -> EngineRuntime? = { null },
    ): RuntimeAdmissionResult {
        currentCoroutineContext().ensureActive()
        checkDeadline(deadline)
        requireProtocol(request.identity.engine == registration.descriptor.id)
        val factory = registration.factory.value
        val policy = factory.runtimeRequirements ?: return RuntimeAdmissionResult.Unavailable
        requireProtocol(policy.engine == registration.descriptor.id)
        var promotion: EngineRuntimePromotion? = null
        var isTransferred = false
        try {
            val result = withTimeout(-deadline.elapsedNow()) {
                var candidate: EngineRuntimeCandidate? = null
                try {
                    recheck()
                    checkDeadline(deadline)
                    adapterCall(log, "open restricted candidate") {
                        // Capture inside the dispatcher boundary: prompt cancellation can drop its result.
                        withContext(context.io) { candidate = factory.openRuntimeCandidate(request, deadline) }
                    }
                    val acquired = candidate ?: return@withTimeout RuntimeAdmissionResult.Unavailable
                    requireProtocol(acquired.request == request)
                    val decision = assessEngineCompatibility(policy, acquired.evidence)
                    if (!decision.isBaselineAllowed) return@withTimeout RuntimeAdmissionResult.Rejected(decision)
                    val stamp = requireNotNull(decision.stamp)
                    recheck()
                    checkDeadline(deadline)
                    val reused = reuseOrRetire(decision)
                    recheck()
                    checkDeadline(deadline)
                    if (reused != null) {
                        requireRuntime(reused, request.identity, stamp)
                        return@withTimeout RuntimeAdmissionResult.Accepted(reused, decision, isNew = false)
                    }
                    adapterCall(log, "promote restricted candidate") {
                        withContext(context.io) { promotion = acquired.promote(stamp, deadline) }
                    }
                    val promoted = requireNotNull(promotion)
                    requireProtocol(promoted.stamp == stamp)
                    requireRuntime(promoted.runtime, request.identity, stamp)
                    RuntimeAdmissionResult.Accepted(promoted.runtime, decision)
                } finally {
                    val owned = candidate
                    if (owned != null) withContext(NonCancellable) { closeCandidate(owned, request.identity) }
                }
            }
            // Cleanup may suspend; validate again before ownership passes to the pool/caller.
            checkDeadline(deadline)
            if (result is RuntimeAdmissionResult.Accepted) {
                withTimeout(-deadline.elapsedNow()) { recheck() }
                requireRuntime(result.runtime, request.identity, result.stamp)
            }
            checkDeadline(deadline)
            currentCoroutineContext().ensureActive()
            isTransferred = result is RuntimeAdmissionResult.Accepted
            return result
        } finally {
            val orphan = promotion?.runtime?.takeUnless { isTransferred }
            if (orphan != null) {
                withContext(NonCancellable) { closeRuntime(orphan, request.identity) }
            }
        }
    }

    private suspend fun closeCandidate(candidate: EngineRuntimeCandidate, expected: RuntimeIdentity) {
        adapterCall(log, "close restricted candidate") { cleanup.close(candidate, expected) }
    }

    private suspend fun closeRuntime(runtime: EngineRuntime, expected: RuntimeIdentity) {
        adapterCall(log, "close untransferred runtime") { cleanup.close(runtime, expected) }
    }

    private fun requireRuntime(runtime: EngineRuntime, identity: RuntimeIdentity, stamp: EngineCompatibilityStamp) {
        requireProtocol(runtime.identity == identity && runtime.compatibilityStamp == stamp && !runtime.isClosed)
    }

    private fun checkDeadline(deadline: TimeMark) {
        if (deadline.hasPassedNow()) fail(EngineFailure.Transport(TransportFailureReason.Timeout))
    }

    private fun requireProtocol(isValid: Boolean) {
        if (!isValid) fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    }
}

/**
 * Profile owner that retains each resource before close and removes it only after successful cleanup.
 * Initial close and retries are serialized by resource identity. Failed or cancelled close remains owned for
 * profile/app shutdown retry; no default/no-op implementation is safe. The expected identity comes from the
 * host request, never the rejected resource itself. Callers use NonCancellable cleanup.
 */
internal interface RuntimeAdmissionCleanup {
    /** Closes remaining restricted resources, retaining them if cleanup fails. */
    suspend fun close(candidate: EngineRuntimeCandidate, expected: RuntimeIdentity)

    /** Closes an untransferred runtime, retaining it if cleanup fails. */
    suspend fun close(runtime: EngineRuntime, expected: RuntimeIdentity)
}
