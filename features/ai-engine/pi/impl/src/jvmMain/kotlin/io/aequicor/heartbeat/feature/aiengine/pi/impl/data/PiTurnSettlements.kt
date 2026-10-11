package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome

/** Retains observed terminal evidence for storage retries and blocks admission through live-state publication. */
internal class PiTurnSettlements(
    private val journal: () -> PiTurnJournal,
    private val failed: suspend (EngineFailure) -> Unit,
    private val answers: (TurnId) -> Map<String, TurnId?> = { emptyMap() },
) {
    private var pending: Turn? = null
    private var publishers = 0
    val isPublishing: Boolean get() = publishers > 0

    suspend fun publish(turn: Turn, outcome: TurnOutcome, apply: suspend (PiTurnRecord) -> Unit) {
        if (pending?.id != turn.id) pending = turn.copy(outcome = outcome)
        publishers++
        try {
            val receipt = persist(turn.id, outcome)
            apply(receipt)
        } catch (error: EngineException) {
            failed(error.failure)
            throw error
        } finally {
            publishers--
        }
    }

    suspend fun reconcile(completed: ActiveSessionIntent.Internal.Finished): ActiveSessionIntent.Internal.Finished {
        val receipt = persist(completed.turn, completed.outcome)
        return completed.copy(outcome = checkNotNull(receipt.turn.outcome))
    }

    private suspend fun persist(turn: TurnId, outcome: TurnOutcome): PiTurnRecord {
        val known = pending?.takeIf { it.id == turn }?.outcome ?: outcome
        val receipt = journal().finish(turn, known, answers(turn))
            ?: piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        if (pending?.id == turn) pending = null
        return receipt
    }
}
