package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/** The coordinator retains this exact gate; cancel and native begin share the durable journal barrier. */
internal class StudioHelperSubmission(
    private val attempts: StudioHelperAttempts,
    private val helper: HelperId,
    private val request: RequestId,
) : StudioSubmissionGate {
    private val log = Log.tag("StudioHelperSubmission")
    private val cancelled = MutableStateFlow(false)
    override val isCancelled: Boolean get() = cancelled.value

    override suspend fun begin() {
        if (!attempts.begin(helper, request)) {
            throw CancellationException("Helper native submission is revoked or owned")
        }
    }

    override suspend fun cancel(): Boolean {
        val receipt = attempts.cancelBeforeSubmission(helper, request)
        val isRevoked = receipt.phase == StudioHelperPhase.NotSubmitted
        log.v { "Helper preparation revocation confirmed=$isRevoked" }
        cancelled.value = isRevoked
        return isRevoked
    }

    override suspend fun awaitRevocation(): CancellationException? =
        if (attempts.awaitSubmissionBoundary(helper, request) == StudioHelperPhase.NotSubmitted) {
            log.v { "Observed durable helper preparation revocation" }
            cancelled.value = true
            CancellationException("Helper preparation was revoked")
        } else {
            null
        }
}
