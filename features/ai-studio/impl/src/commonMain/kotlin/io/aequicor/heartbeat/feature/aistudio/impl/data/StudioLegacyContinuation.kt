package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask

/** A producer claim classifies historical evidence only; it cannot reconstruct execution configuration. */
internal data class StudioLegacyHumanClaim(val request: StudioTurnRequest, val producer: WorktreeTask) {
    fun confirm(record: StudioChatRecord, observation: StudioLegacyObservation): StudioLegacyObservation {
        requireContinuationClaim(producer, request)
        check(
            record.identity?.origin == StudioIdentityOrigin.UserConversation &&
                record.logicalSession?.kind == SessionKind.Session &&
                record.helper == null && record.organismId == null &&
                !record.hasAttemptJournal,
        ) { "Legacy source is not a proven original human conversation" }
        val authority = record.humanAuthority?.snapshot() as? TrustedUserInstructions.Complete
        check(authority?.messages?.any { it.request == request.continuationOf } == true) {
            "Legacy source has no complete original human ledger"
        }
        val run = checkNotNull(producer.run)
        check(
            observation.request == request.continuationOf && observation.phase == StudioLegacyPhase.Terminal &&
                observation.turn != null && observation.session == run.session && observation.turn == run.turn &&
                observation.terminalResult()?.outcome == run.outcome,
        ) { "Legacy source lacks exact native terminal evidence" }
        return observation.copy(isHumanSourceConfirmed = true)
    }
}

/** Historical human provenance is not a substitute for missing settings, model or authority at native entry. */
internal class StudioLegacySnapshotUnavailable(val source: RequestId) :
    IllegalStateException("Historical execution configuration is unavailable; start a new independent message")
