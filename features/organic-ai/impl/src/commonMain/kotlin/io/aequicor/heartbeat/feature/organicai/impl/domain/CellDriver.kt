package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.Settlement
import io.aequicor.heartbeat.feature.organicai.api.Work
import io.aequicor.heartbeat.feature.organicai.api.cell

/**
 * Runs one turn of a cell: opens its session, submits the turn's prompt (or adopts the native turn a recovery finds
 * still running), reports acceptance and pending permission requests, and settles the turn with its final answer.
 * The organism is journaled before a submission, so a request id never reaches an engine unsaved and a restart never
 * reuses it. Feedback the machine no longer takes means the cell ended meanwhile: the driver lets go and stops.
 */
internal class CellDriver(private val cells: CellSessions, private val journal: OrganismJournal) {
    private val log = Log.tag("CellDriver")

    suspend fun drive(organism: Organism, id: CellId, request: RequestId, machine: EffectScope<OrganicAiIntent>) {
        val cell = organism.cell(id)
        val phase = cell?.phase as? CellPhase.Working
        val target = organism.target
        if (cell == null || phase?.request != request || target == null) {
            log.w { "organism ${organism.id.value} cell ${id.value}: nothing to drive" }
            return
        }
        val key = CellKey(organism.id, id)
        log.i { "organism ${organism.id.value} cell ${id.value}: ${phase.kind()} turn ${cell.turns} starts" }
        val handle = cells.open(key, CellRoute(target, organism.workspace, organism.trust), cell.session, cell.turns)
        if (cell.session == null) {
            val bound = OrganicAiIntent.Internal.SessionBound(organism.id, id, request, handle.session)
            if (machine.send(bound) != SendResult.Accepted) return abandon(key, handle, turn = null)
        }
        val turn = phase.takeIf { it.isRecovery }?.let { handle.activeTurn() } ?: run {
            journal.save(organism)
            val attachments = organism.attachments.takeIf { phase.work == Work.Genesis }.orEmpty()
            handle.submit(request, turnPrompt(organism, cell), organism.trust, attachments)
        }
        val accepted = OrganicAiIntent.Internal.TurnAccepted(organism.id, id, request, turn)
        if (machine.send(accepted) != SendResult.Accepted) return abandon(key, handle, turn)
        val outcome = handle.await(turn) { pending ->
            machine.send(OrganicAiIntent.Internal.PermissionsChanged(organism.id, id, request, pending))
        }
        val settlement = settlement(handle, turn, outcome)
        val result = machine.send(OrganicAiIntent.Internal.TurnSettled(organism.id, id, request, settlement))
        log.i {
            "organism ${organism.id.value} cell ${id.value}: turn ${cell.turns} ${settlement.kind()}, machine $result"
        }
    }

    /**
     * An unknown outcome is neither success nor failure: only an answer that surely is this turn's settles it as
     * answered, otherwise the turn broke (a zygote stalls and can be resumed instead of completing on nothing).
     */
    private suspend fun settlement(handle: CellHandle, turn: TurnId, outcome: TurnOutcome): Settlement =
        when (outcome) {
            TurnOutcome.Completed -> Settlement.Answered(handle.answer(turn) ?: NO_ANSWER)

            TurnOutcome.Unknown -> handle.answer(turn, isUnconfirmed = true)?.let(Settlement::Answered)
                ?: Settlement.Broke(Breakdown.Unconfirmed)

            TurnOutcome.Cancelled -> Settlement.Broke(Breakdown.Interrupted)

            is TurnOutcome.Failed -> Settlement.Broke(Breakdown.Engine(outcome.failure))
        }

    /** The cell ended while its turn was being set up: its new session and turn must not live on. */
    private suspend fun abandon(key: CellKey, handle: CellHandle, turn: TurnId?) {
        log.i { "organism ${key.organism.value} cell ${key.cell.value} ended meanwhile; abandoning its session" }
        turn?.let { handle.cancel(it) }
        cells.release(key, handle.session, ReleaseMode.Lyse)
    }

    private fun CellPhase.Working.kind(): String = when {
        isRecovery -> "recovery"
        work == Work.Genesis -> "genesis"
        else -> "inbox reminder"
    }

    private fun Settlement.kind(): String = when (this) {
        is Settlement.Answered -> "answered"
        is Settlement.Broke -> "broke (${breakdown.describe()})"
    }

    private companion object {
        const val NO_ANSWER = "(The cell ended its turn without a written answer.)"
    }
}
