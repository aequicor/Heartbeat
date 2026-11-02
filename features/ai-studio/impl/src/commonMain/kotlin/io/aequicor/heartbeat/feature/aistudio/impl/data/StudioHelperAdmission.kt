package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Every new Studio send receives a durable admission receipt, independently of the conversation's role. */
@Inject
internal class StudioHelperAdmission(
    private val attempts: StudioHelperAttempts,
    private val admissions: StudioHelperPromptAdmissions,
    private val runs: Lazy<StudioHelperRuns>? = null,
    private val continuations: StudioContinuationAdmission? = null,
) {
    private val log = Log.tag("StudioHelperAdmission")

    /**
     * Keeps the existing refusal explicit after new-send admission moves behind recovery. Until the coordinator
     * binds execution Q to actual native R, stop in these chats would otherwise retire Q while R keeps running.
     */
    fun requireNativeRecovery(record: StudioChatRecord) {
        check(record.helper == null && record.logicalSession?.kind != SessionKind.Subsession) {
            "Journaled recovery requires exact adopted-request stop routing"
        }
    }

    /** Bookkeeping follows durable admission; a failed record write cannot strand an unreturned wrapper. */
    suspend fun prepareNewTurn(
        record: StudioChatRecord,
        request: StudioTurnRequest,
        recordStarted: suspend () -> Unit,
    ): StudioTurnRequest {
        val admitted = prepare(record, request)
        var isRecorded = false
        try {
            recordStarted()
            isRecorded = true
            return admitted
        } finally {
            if (!isRecorded) withContext(NonCancellable) { (admitted.submission as? StudioHelperSubmission)?.cancel() }
        }
    }

    /** Do not overwrite a legacy root's only native request address while that request may still be running. */
    private suspend fun requireLegacyTerminal(record: StudioChatRecord, helper: HelperId) {
        if (record.hasAttemptJournal || record.helper != null || record.ref == null) return
        val previous = record.lastRunRequest
            ?: throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        if (attempts.receipt(helper, previous) != null) return
        val observation = runs?.value?.reconcileLegacy(helper, previous)
        when (observation?.phase) {
            StudioLegacyPhase.Idle, StudioLegacyPhase.Terminal -> attempts.rememberLegacy(helper, observation)

            StudioLegacyPhase.Active, StudioLegacyPhase.Unknown, null ->
                throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        }
        log.v { "Persisted legacy native observation before first universal journal admission" }
    }

    suspend fun prepare(record: StudioChatRecord, request: StudioTurnRequest): StudioTurnRequest {
        val helper = HelperId(record.id)
        val supplied = request.submission as? StudioHelperSubmission
        if (request.continuationOf != null && supplied == null) {
            return checkNotNull(continuations) { "Continuation admission is unavailable" }.prepare(record, request)
        }
        if (supplied != null) {
            check(supplied.matches(helper, request.request)) { "The helper submission belongs to another request" }
            check(request.origin.accepts(supplied.policy())) { "The submission origin contradicts its request journal" }
            return request
        }
        check(
            request.origin != StudioTurnOrigin.HelperWork,
        ) { "Helper work requires an authenticated lease submission" }
        requireLegacyTerminal(record, helper)
        val policy = request.origin.deliveryPolicy()
        val previous = attempts.unresolved(helper)
        if (previous != null && previous != request.request) {
            val terminal = runs?.value?.helperTerminal(helper, previous)
                ?: throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
            attempts.terminal(helper, previous, terminal)
        }
        // A scheduled wake keeps its request id across deliveries; a revoked earlier preparation may be redone.
        val prepared = attempts.prepareDelivery(helper, HelperPrompt(request.request, request.prompt), policy)
        check(prepared.isNew) { "The helper request is already owned" }
        log.v { "Reserved helper request through common admission" }
        return request.copy(
            submission = StudioHelperSubmission(attempts, admissions, helper, request.request, request.submission),
        )
    }
}
