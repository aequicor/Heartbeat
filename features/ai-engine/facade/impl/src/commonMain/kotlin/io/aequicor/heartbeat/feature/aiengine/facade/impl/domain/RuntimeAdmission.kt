package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
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
    data class Accepted(val runtime: EngineRuntime, val decision: EngineCompatibilityDecision) :
        RuntimeAdmissionResult {
        init {
            require(decision.isBaselineAllowed)
        }

        val stamp: EngineCompatibilityStamp = requireNotNull(decision.stamp)

        override fun toString(): String = "RuntimeAdmissionResult.Accepted"
    }
}

/**
 * Two-phase acquisition seam. Not yet used by RuntimePool: pool reuse, create/resume gating and adapter opt-in
 * are separate integration work. No legacy runtime method is called here. Cleanup is awaited even past the
 * deadline; a caller requiring bounded UI wait must retain a separate worker owner until cleanup completes.
 */
internal class RuntimeAdmission(private val context: FacadeContext, private val cleanup: RuntimeAdmissionCleanup) {
    private val log = Log.tag("RuntimeAdmission")

    /**
     * Acquires one candidate and transfers it only while [recheck] still authorizes the canonical route and
     * the supplied launch snapshot. Rechecking must reject a changed source, launch or configuration contract.
     */
    suspend fun acquire(
        registration: EngineRegistration,
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        recheck: suspend () -> Unit,
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
                    adapterCall(log, "promote restricted candidate") {
                        withContext(context.io) { promotion = acquired.promote(stamp, deadline) }
                    }
                    val promoted = requireNotNull(promotion)
                    requireProtocol(promoted.runtime.identity == request.identity && promoted.stamp == stamp)
                    RuntimeAdmissionResult.Accepted(promoted.runtime, decision)
                } finally {
                    val owned = candidate
                    if (owned != null) withContext(NonCancellable) { closeCandidate(owned) }
                }
            }
            // Cleanup may suspend; validate again before ownership passes to the pool/caller.
            checkDeadline(deadline)
            if (result is RuntimeAdmissionResult.Accepted) {
                withTimeout(-deadline.elapsedNow()) { recheck() }
            }
            checkDeadline(deadline)
            currentCoroutineContext().ensureActive()
            isTransferred = result is RuntimeAdmissionResult.Accepted
            return result
        } finally {
            val orphan = promotion?.runtime?.takeUnless { isTransferred }
            if (orphan != null) {
                withContext(NonCancellable) { closeRuntime(orphan) }
            }
        }
    }

    private suspend fun closeCandidate(candidate: EngineRuntimeCandidate) {
        cleanup.retain(candidate)
        withContext(context.io) {
            adapterCall(log, "close restricted candidate") { candidate.close() }
        }
        cleanup.releaseClosed(candidate)
    }

    private suspend fun closeRuntime(runtime: EngineRuntime) {
        cleanup.retain(runtime)
        withContext(context.io) {
            adapterCall(log, "close untransferred runtime") { runtime.close() }
        }
        cleanup.releaseClosed(runtime)
    }

    private fun checkDeadline(deadline: TimeMark) {
        if (deadline.hasPassedNow()) fail(EngineFailure.Transport(TransportFailureReason.Timeout))
    }

    private fun requireProtocol(isValid: Boolean) {
        if (!isValid) fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    }
}

/**
 * Profile owner of resources during cleanup and after failed close. All methods are synchronous, IO-free and
 * must not throw. Serialize retries with the acquisition attempt, never run two closes concurrently. Keep
 * each resource until a later close succeeds, including through profile shutdown; never replace with a no-op.
 * The pool integration must supply this owner before enabling the acquisition seam.
 */
internal interface RuntimeAdmissionCleanup {
    /** Retains restricted resources before attempting cleanup. */
    fun retain(candidate: EngineRuntimeCandidate)

    /** Retains an untransferred runtime before attempting cleanup. */
    fun retain(runtime: EngineRuntime)

    /** Removes ownership only after candidate close completed successfully. */
    fun releaseClosed(candidate: EngineRuntimeCandidate)

    /** Removes ownership only after runtime close completed successfully. */
    fun releaseClosed(runtime: EngineRuntime)
}
