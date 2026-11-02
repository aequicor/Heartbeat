package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CancellationException

/** Mandatory recovery evidence belongs to the reserved delivery, independently of optional observer callbacks. */
@Inject
internal class StudioRecoveryJournal(private val attempts: StudioHelperAttempts) {
    /** Only a trusted graph producer may supply candidates; this host freezes its verified work declaration. */
    suspend fun reserve(request: StudioTurnRequest, record: StudioChatRecord): StudioRecoveryWitness {
        val origin = request.origin as? StudioTurnOrigin.GraphWork ?: error("Recovery has no trusted work root")
        val delivery = checkNotNull(request.submission?.recoveryDelivery) { "Recovery gate has no delivery claim" }
        requireDelivery(request, delivery)
        val target = checkNotNull(record.target) { "Recovery needs a saved native target" }
        val witness = StudioRecoveryWitness(
            request.request,
            origin.recoveryRoot,
            request.recoveryRequests.toSet(),
            checkNotNull(record.ref) { "Recovery needs a saved native session" },
            delivery = delivery,
            route = StudioRecoveryRoute(target, record.resolvedExecutionWorkspace()),
        )
        witness.requireRoute(record)
        return attempts.prepareRecovery(HelperId(record.id), witness)
    }

    suspend fun requireReserved(request: StudioTurnRequest, record: StudioChatRecord) {
        val delivery = checkNotNull(request.submission?.recoveryDelivery)
        requireDelivery(request, delivery)
        val witness = current(delivery)
        check(witness.candidates == request.recoveryRequests.toSet()) { "Reserved recovery candidates changed" }
        witness.requireRoute(record)
    }

    suspend fun stop(delivery: StudioRecoveryDelivery) = attempts.changeRecoveryDelivery(delivery) { it.stopped() }

    suspend fun isStopRequested(delivery: StudioRecoveryDelivery): Boolean = current(delivery).isStopRequested

    private suspend fun current(delivery: StudioRecoveryDelivery): StudioRecoveryWitness {
        val witness = checkNotNull(attempts.recovery(HelperId(delivery.chat), delivery.execution))
        if (witness.delivery != delivery || witness.observerSuccessor != null) {
            throw CancellationException("Recovery reservation changed")
        }
        return witness
    }

    private fun requireDelivery(request: StudioTurnRequest, delivery: StudioRecoveryDelivery) {
        check(delivery.chat == request.id && delivery.execution == request.request) { "Recovery delivery changed" }
        check(delivery.fingerprint == request.recoveryPayloadFingerprint()) { "Recovery work payload changed" }
        check(request.environment == null) { "Outside recovery needs a durable environment contract" }
    }

    suspend fun idle(delivery: StudioRecoveryDelivery, session: SessionRef) =
        attempts.changeRecoveryDelivery(delivery) { it.idle(session) }

    suspend fun observed(delivery: StudioRecoveryDelivery, evidence: StudioRecoveryEvidence): StudioRecoveryWitness {
        val witness = attempts.changeRecoveryDelivery(delivery) {
            it.observed(evidence.request, evidence.session, evidence.turn, evidence.outcome?.recoveryOutcome())
        }
        rememberTerminal(delivery, evidence)
        return witness
    }

    /** A known receipt turn may enrich terminal-only inspection; a missing native turn is never fabricated. */
    private suspend fun rememberTerminal(delivery: StudioRecoveryDelivery, evidence: StudioRecoveryEvidence) {
        val outcome = evidence.outcome?.takeUnless { it == TurnOutcome.Unknown } ?: return
        val helper = HelperId(delivery.chat)
        val receipt = attempts.receipt(helper, evidence.request) ?: return
        val turn = evidence.turn ?: receipt.turn
        attempts.terminal(
            helper,
            evidence.request,
            StudioHelperTerminal(evidence.session, turn, outcome.recoveryOutcome(), ""),
        )
    }

    suspend fun inspectionTarget(delivery: StudioRecoveryDelivery): RequestId? = current(delivery).inspectionTarget

    suspend fun submitting(delivery: StudioRecoveryDelivery) =
        attempts.changeRecoveryDelivery(delivery) { it.submitting() }

    suspend fun unknown(delivery: StudioRecoveryDelivery) = attempts.changeRecoveryDelivery(delivery) { it.unknown() }

    suspend fun noSend(proof: StudioRecoveryNoSendProof) =
        attempts.closeRecoveryNoSend(HelperId(proof.delivery.chat), proof)
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
