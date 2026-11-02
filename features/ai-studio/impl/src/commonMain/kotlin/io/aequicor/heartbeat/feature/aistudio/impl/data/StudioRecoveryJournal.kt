package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome

/** Mandatory recovery evidence belongs to the reserved delivery, independently of optional observer callbacks. */
@Inject
internal class StudioRecoveryJournal(private val attempts: StudioHelperAttempts) {
    suspend fun idle(delivery: StudioRecoveryDelivery, session: SessionRef) =
        attempts.changeRecoveryDelivery(delivery) { it.idle(session) }

    suspend fun observed(delivery: StudioRecoveryDelivery, evidence: StudioRecoveryEvidence) =
        attempts.changeRecoveryDelivery(delivery) {
            it.observed(evidence.request, evidence.session, evidence.turn, evidence.outcome?.recoveryOutcome())
        }

    suspend fun submitting(delivery: StudioRecoveryDelivery) =
        attempts.changeRecoveryDelivery(delivery) { it.submitting() }

    suspend fun unknown(delivery: StudioRecoveryDelivery) = attempts.changeRecoveryDelivery(delivery) { it.unknown() }

    suspend fun noSend(proof: StudioRecoveryNoSendProof) =
        attempts.changeRecoveryDelivery(proof.delivery) { it.noSend(proof) }
}

/** A terminal-only inspection deliberately carries no invented turn id. */
internal data class StudioRecoveryEvidence(
    val session: SessionRef,
    val request: RequestId,
    val turn: TurnId? = null,
    val outcome: TurnOutcome? = null,
)

private fun TurnOutcome.recoveryOutcome(): StudioHelperTerminalOutcome = when (this) {
    TurnOutcome.Completed -> StudioHelperTerminalOutcome.Completed
    TurnOutcome.Cancelled -> StudioHelperTerminalOutcome.Cancelled
    TurnOutcome.Unknown -> StudioHelperTerminalOutcome.Unknown
    is TurnOutcome.Failed -> StudioHelperTerminalOutcome.Failed
}
