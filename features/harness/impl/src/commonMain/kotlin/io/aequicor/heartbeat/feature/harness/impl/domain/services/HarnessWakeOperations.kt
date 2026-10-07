package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.deliveryRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * The profile owns an admitted immutable submission, independently of its script's wait. Cancellation or timeout
 * of the caller cannot free an uncertain reservation or cause the same text to be submitted again. No author
 * callbacks run here: [isAdmitted] is a host authority check that may suspend.
 */
internal class HarnessWakeOperations(
    private val scope: CoroutineScope,
    private val port: HarnessWakePort,
    private val quotas: HarnessWakeQuotas,
    private val origins: HarnessRequestOrigins,
    private val clock: Clock,
) {
    private val log = Log.tag("HarnessServices")

    @HighFrequency
    suspend fun schedule(submission: HarnessWakeSubmission, isAdmitted: suspend () -> Boolean): WakeId {
        val request = submission.request
        check(request.ownerFeature == HARNESS_WAKE_OWNER) { "Invalid harness wake owner" }
        check(isAdmitted()) { "Harness wake is no longer admitted" }
        val result = CompletableDeferred<WakeId>()
        val job = scope.launch {
            try {
                submit(submission, isAdmitted)
                result.complete(request.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(harnessScriptFailure(error)) { "Wake submission did not complete" }
                val failure = IllegalStateException("Harness wake submission failed")
                result.completeExceptionally(failure)
            }
        }
        job.invokeOnCompletion { error ->
            if (error != null) result.completeExceptionally(error)
        }
        return result.await()
    }

    private suspend fun submit(submission: HarnessWakeSubmission, isAdmitted: suspend () -> Boolean) {
        check(isAdmitted()) { "Harness wake is no longer admitted" }
        val harness = submission.harness
        val request = submission.request
        val origin = submission.origin
        val isSend = submission.isSend
        quotas.reserve(HarnessWakeReservation(request.id, harness, request.session, isSend), port::snapshot)
        var hasSubmissionStarted = false
        try {
            check(isAdmitted()) { "Harness wake admission was revoked" }
            origins.register(request.session, request.id.deliveryRequestId(), origin)
            check(isAdmitted()) { "Harness wake admission was revoked" }
            currentCoroutineContext().ensureActive()
            hasSubmissionStarted = true
            when (port.schedule(request, clock.now())) {
                HarnessWakeReceipt.Scheduled -> quotas.acknowledged(request.id)

                HarnessWakeReceipt.Rejected -> {
                    quotas.rejected(request.id)
                    error("Scheduler rejected harness wake")
                }

                HarnessWakeReceipt.Unknown -> error("Harness wake acceptance is uncertain")
            }
        } finally {
            // Even a lost ancestry ACK cannot mean scheduler acceptance before this boundary.
            if (!hasSubmissionStarted) withContext(NonCancellable) { quotas.rejected(request.id) }
        }
    }
}

/** One immutable host-owned scheduling operation. */
internal data class HarnessWakeSubmission(
    val harness: HarnessId,
    val request: WakeRequest,
    val origin: HarnessCallOrigin,
    val isSend: Boolean,
) {
    override fun toString(): String = "HarnessWakeSubmission(***)"
}
