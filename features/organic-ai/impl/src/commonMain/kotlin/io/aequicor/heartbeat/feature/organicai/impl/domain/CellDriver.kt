package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.Settlement
import io.aequicor.heartbeat.feature.organicai.api.Work
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.api.isSandboxed
import io.aequicor.heartbeat.feature.organicai.api.needsSandbox
import io.aequicor.heartbeat.feature.organicai.api.targetOf
import io.aequicor.heartbeat.feature.organicai.api.workspaceOf
import kotlinx.coroutines.CancellationException

/**
 * Runs one turn of a cell: opens its session, submits the turn's prompt (or adopts the native turn a recovery finds
 * still running), reports acceptance and pending permission requests, and settles the turn with its final answer.
 * The organism is journaled before a submission, so a request id never reaches an engine unsaved and a restart never
 * reuses it. Feedback the machine no longer takes means the cell ended meanwhile: the driver lets go and stops.
 * A cell of a sandbox organism gets its own checkout first: it is prepared and saved before the session is created, so
 * the session is never opened in the organism's project, also not on a retry or after a restart.
 */
internal class CellDriver(
    private val cells: CellSessions,
    private val journal: OrganismJournal,
    private val workspaces: CellWorkspaces,
) {
    private val log = Log.tag("CellDriver")

    suspend fun drive(organism: Organism, id: CellId, request: RequestId, machine: EffectScope<OrganicAiIntent>) {
        val cell = organism.cell(id)
        val phase = cell?.phase as? CellPhase.Working
        val target = cell?.let { organism.targetOf(it) }
        if (cell == null || phase?.request != request || target == null) {
            log.w { "organism ${organism.id.value} cell ${id.value}: nothing to drive" }
            return
        }
        log.i { "organism ${organism.id.value} cell ${id.value}: ${phase.kind()} turn ${cell.turns} starts" }
        val route = routeOf(organism, cell, target, request, machine) ?: return
        runTurn(organism, cell, phase, route, machine)
    }

    /** The turn of [phase] in the cell's session, which opens on [route]. */
    private suspend fun runTurn(
        organism: Organism,
        cell: Cell,
        phase: CellPhase.Working,
        route: CellRoute,
        machine: EffectScope<OrganicAiIntent>,
    ) {
        val key = CellKey(organism.id, cell.id)
        val handle = cells.open(key, route, cell.session, cell.turns)
        if (cell.session == null) {
            val bound = OrganicAiIntent.Internal.SessionBound(organism.id, cell.id, phase.request, handle.session)
            if (machine.send(bound) != SendResult.Accepted) return abandon(key, handle, turn = null, CellRun.None)
        }
        val live = phase.takeIf { it.isRecovery }?.let { handle.activeTurn() }
        val run = runOf(organism, cell.id, phase.request, handle.session, live)
        var turn = live
        try {
            turn = live ?: submit(organism, cell, phase, handle)
            run.accepted(handle.session, turn)
            val accepted = OrganicAiIntent.Internal.TurnAccepted(organism.id, cell.id, phase.request, turn)
            if (machine.send(accepted) != SendResult.Accepted) return abandon(key, handle, turn, run)
            val outcome = handle.await(turn) { pending ->
                machine.send(OrganicAiIntent.Internal.PermissionsChanged(organism.id, cell.id, phase.request, pending))
            }
            val settlement = settlement(handle, turn, outcome)
            run.settled(handle.session, turn, outcome)
            val settled = OrganicAiIntent.Internal.TurnSettled(organism.id, cell.id, phase.request, settlement)
            val result = machine.send(settled)
            log.i {
                "organism ${organism.id.value} cell ${cell.id.value}: turn ${cell.turns} ${settlement.kind()}, " +
                    "machine $result"
            }
        } catch (e: CancellationException) {
            // The profile sleeps or the effect is replaced: the engine keeps the turn, so the run stays with it.
            throw e
        } catch (e: Exception) {
            run.lost(handle.session, turn)
            throw e
        }
    }

    /** Journals the organism, then hands the turn's prompt and inputs to the engine. */
    private suspend fun submit(organism: Organism, cell: Cell, phase: CellPhase.Working, handle: CellHandle): TurnId {
        journal.save(organism)
        return handle.submit(phase.request, turnPrompt(organism, cell), organism.trust, phase.inputs(organism))
    }

    /**
     * The worktree run of the turn: the one of a native turn that survived a restart ([live]) when the sandbox still
     * holds it, else a new one for [request]. A cell that shares the organism's project has no run.
     */
    private suspend fun runOf(
        organism: Organism,
        cell: CellId,
        request: RequestId,
        session: SessionRef,
        live: TurnId?,
    ): CellRun = when {
        !organism.isSandboxed -> CellRun.None
        else -> live?.let { workspaces.adopt(organism, cell, session, it) } ?: workspaces.begin(organism, cell, request)
    }

    /**
     * How the cell's session runs. A sandbox cell's own checkout is prepared and bound first, so the session is
     * created in it. Null when the machine no longer takes the binding: the cell ended meanwhile.
     */
    private suspend fun routeOf(
        organism: Organism,
        cell: Cell,
        target: EngineTarget,
        request: RequestId,
        machine: EffectScope<OrganicAiIntent>,
    ): CellRoute? {
        var workspace = organism.workspaceOf(cell)
        if (organism.needsSandbox(cell)) {
            val own = workspaces.prepare(organism, cell.id)
            val bound = machine.send(OrganicAiIntent.Internal.WorkspaceBound(organism.id, cell.id, request, own))
            if (bound != SendResult.Accepted) {
                log.i { "organism ${organism.id.value} cell ${cell.id.value} ended before its sandbox was bound" }
                return null
            }
            workspace = own
        }
        return CellRoute(
            target,
            workspace,
            organism.trust,
            cell.configuration?.reasoningEffort,
            organism.target?.takeIf { it != target },
        )
    }

    /** The profile-owned inputs the turn's work carries: the goal's at genesis, the message's at a follow-up. */
    private fun CellPhase.Working.inputs(organism: Organism): List<ResourceRef> = when (val current = work) {
        Work.Genesis -> organism.attachments
        is Work.FollowUp -> current.attachments
        Work.CheckInbox, is Work.Letters -> emptyList()
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
    private suspend fun abandon(key: CellKey, handle: CellHandle, turn: TurnId?, run: CellRun) {
        log.i { "organism ${key.organism.value} cell ${key.cell.value} ended meanwhile; abandoning its session" }
        turn?.let {
            handle.cancel(it)
            run.settled(handle.session, it, TurnOutcome.Cancelled)
        }
        cells.release(key, handle.session, ReleaseMode.Lyse)
    }

    private fun CellPhase.Working.kind(): String = when {
        isRecovery -> "recovery"
        work == Work.Genesis -> "genesis"
        work is Work.FollowUp -> "follow-up"
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
