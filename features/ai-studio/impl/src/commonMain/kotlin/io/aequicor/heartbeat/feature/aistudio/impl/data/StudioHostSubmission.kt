package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRunAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext

/**
 * Keeps the caller's cancellation effective while profile-owned preparation suspends. The coordinator retains this
 * gate; helper admission wraps it as its prior gate. Only a successful host claim may cross the existing boundary.
 */
internal class StudioHostSubmission(
    private val admission: StudioRunAdmission,
    private val caller: CoroutineContext,
    private val submission: StudioRunSubmission = StudioRunSubmission(),
) : StudioSubmissionGate by submission {
    private val log = Log.tag("StudioHostSubmission")
    private val begun = MutableStateFlow(false)

    override suspend fun begin() {
        check(begun.compareAndSet(false, true)) { "Host admission was already attempted" }
        log.v { "Claimed host submission admission" }
        caller.ensureActive()
        currentCoroutineContext().ensureActive()
        if (submission.isCancelled || !admission.admit()) {
            submission.cancel()
            throw CancellationException("Host run admission was refused")
        }
        caller.ensureActive()
        currentCoroutineContext().ensureActive()
        submission.begin()
    }
}
