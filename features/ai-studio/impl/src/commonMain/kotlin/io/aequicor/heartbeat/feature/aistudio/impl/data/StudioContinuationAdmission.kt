package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.flow.first

/** Only a claimed virtual lifecycle action may inherit the interrupted execution's authority. */
@Inject
internal class StudioContinuationAdmission(
    private val attempts: StudioHelperAttempts,
    private val admissions: StudioHelperPromptAdmissions,
    private val worktrees: StudioWorktrees,
    private val runs: Lazy<StudioHelperRuns>? = null,
) {
    private val log = Log.tag("StudioContinuationAdmission")

    suspend fun prepare(record: StudioChatRecord, request: StudioTurnRequest): StudioTurnRequest {
        val producer = checkNotNull(worktrees.tasks().first()[record.id]) { "Continuation producer is missing" }
        return prepare(record, request, producer)
    }

    internal suspend fun prepare(
        record: StudioChatRecord,
        request: StudioTurnRequest,
        producer: WorktreeTask,
    ): StudioTurnRequest {
        val helper = HelperId(record.id)
        val sourceId = checkNotNull(request.continuationOf)
        val source = attempts.receipt(helper, sourceId)
        if (source == null) {
            requireContinuationClaim(producer, request)
            val observed = checkNotNull(runs?.value?.reconcileLegacy(helper, sourceId)) {
                "Legacy continuation native evidence is unavailable"
            }
            attempts.rememberLegacy(helper, observed, StudioLegacyHumanClaim(request, producer))
            throw StudioLegacySnapshotUnavailable(sourceId)
        }
        val original = source.executionSnapshot ?: throw StudioLegacySnapshotUnavailable(sourceId)
        original.requireContinuationConnection(record)
        val prepared = attempts.prepareContinuation(helper, request, producer)
        check(prepared.isNew) { "Continuation is already owned or requires reconciliation" }
        val receipt = prepared.receipt
        val snapshot = checkNotNull(receipt.executionSnapshot)
        log.v { "Bound continuation to its original execution snapshot" }
        return request.copy(
            settings = snapshot.settings,
            causes = receipt.causes.orEmpty(),
            origin = checkNotNull(receipt.policy).continuationOrigin(),
            submission = StudioHelperSubmission(attempts, admissions, helper, request.request, request.submission),
        )
    }
}

private fun StudioAttemptPolicy.continuationOrigin(): StudioTurnOrigin = when (this) {
    StudioAttemptPolicy.Conversation -> StudioTurnOrigin.Conversation
    is StudioAttemptPolicy.OwnedHelperWork -> StudioTurnOrigin.HelperWork
    is StudioAttemptPolicy.GraphWork -> StudioTurnOrigin.GraphWork(recoveryRoot)
    StudioAttemptPolicy.UnknownService -> error("Unknown origin cannot continue")
}

/** Admission may restore only fields proven by the durable edge; every other request field stays immutable. */
internal suspend fun StudioTurnRequest.requireAdmission(admitted: StudioTurnRequest) {
    val comparable = admitted.copy(settings = settings, submission = submission)
    val graph = (admitted.submission as? StudioHelperSubmission)?.continuationReceipt()?.graphContinuation
    if (graph != null) {
        val delivery = checkNotNull(submission?.recoveryDelivery)
        check(graph.delivery.copy(claim = delivery.claim) == delivery && graph.child == request) {
            "Graph continuation admission changed its delivery"
        }
        check(origin == StudioTurnOrigin.GraphWork(graph.root) && comparable.copy(causes = causes) == this) {
            "Graph continuation admission changed its request or work"
        }
        val receipt = checkNotNull((admitted.submission as? StudioHelperSubmission)?.continuationReceipt())
        check(admitted.settings == receipt.executionSnapshot?.settings && admitted.causes == receipt.causes.orEmpty()) {
            "Graph continuation changed inherited execution authority"
        }
    } else if (continuationOf == null) {
        check(comparable == this) { "New-send admission changed the request or its provenance" }
    } else {
        check(
            comparable.copy(causes = causes, origin = origin) == this,
        ) { "Continuation admission changed its request" }
        val gate = checkNotNull(admitted.submission as? StudioHelperSubmission)
        val receipt = checkNotNull(gate.continuationReceipt())
        check(gate.matches(HelperId(id), request) && receipt.continuationOf == continuationOf) {
            "Continuation admission belongs to another source"
        }
        check(admitted.origin.accepts(receipt.policy) && admitted.causes == receipt.causes.orEmpty()) {
            "Continuation admission changed inherited provenance"
        }
        check(admitted.settings == receipt.executionSnapshot?.settings) { "Continuation settings changed" }
    }
}
