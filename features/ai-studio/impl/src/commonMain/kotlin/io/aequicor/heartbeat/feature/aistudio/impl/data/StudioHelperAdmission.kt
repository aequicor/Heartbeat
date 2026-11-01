package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt

/** The repository calls this for every run, including manual and scheduled runs in a marked helper chat. */
@Inject
internal class StudioHelperAdmission(
    private val attempts: StudioHelperAttempts,
    private val admissions: StudioHelperPromptAdmissions,
    private val runs: Lazy<StudioHelperRuns>? = null,
) {
    private val log = Log.tag("StudioHelperAdmission")

    suspend fun prepare(record: StudioChatRecord, request: StudioTurnRequest): StudioTurnRequest {
        if (record.helper == null && record.logicalSession?.kind != SessionKind.Subsession) return request
        val helper = HelperId(record.id)
        val supplied = request.submission as? StudioHelperSubmission
        if (supplied != null) {
            check(supplied.matches(helper, request.request)) { "The helper submission belongs to another request" }
            return request
        }
        val previous = attempts.unresolved(helper)
        if (previous != null && previous != request.request) {
            val terminal = runs?.value?.helperTerminal(helper, previous)
                ?: throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
            attempts.terminal(helper, previous, terminal)
        }
        // A scheduled wake keeps its request id across deliveries; a revoked earlier preparation may be redone.
        val prepared = attempts.prepareDelivery(helper, HelperPrompt(request.request, request.prompt))
        check(prepared.isNew) { "The helper request is already owned" }
        log.v { "Reserved helper request through common admission" }
        return request.copy(
            submission = StudioHelperSubmission(attempts, admissions, helper, request.request, request.submission),
        )
    }
}
