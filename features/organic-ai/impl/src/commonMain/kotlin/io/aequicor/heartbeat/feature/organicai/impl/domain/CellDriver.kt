package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
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
 * Runs one turn of a cell: opens its session, connects the organism's harnesses to it, submits the turn's prompt (or
 * adopts the native turn a recovery finds still running), reports acceptance and pending permission requests, and
 * settles the turn with its final answer. The organism is journaled before a submission, so a request id never
 * reaches an engine unsaved and a restart never reuses it. A harness that cannot be connected fails the turn before
 * any prompt: the cell never works in a smaller environment than the user chose. Feedback the machine no longer takes
 * means the cell ended meanwhile: the driver lets go and stops.
 * A cell of a sandbox organism gets its own checkout first: it is prepared and saved before the session is created, so
 * the session is never opened in the organism's project, also not on a retry or after a restart.
 */
internal class CellDriver(
    private val cells: CellSessions,
    private val journal: OrganismJournal,
    private val workspaces: CellWorkspaces,
    private val harnesses: CellHarnesses,
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
        try {
            val route = routeOf(organism, cell, target, request, machine) ?: return
            if (organism.isSandboxed) workspaces.awaitAvailable(organism, id)
            runTurn(organism, cell, phase, route, machine)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!organism.isSandboxed || !workspaces.isPaused(organism, id)) throw error
            workspaces.awaitAvailable(organism, id)
            machine.send(OrganicAiIntent.Internal.WorkspaceContinued(organism.id, id, request))
        }
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
        val driven = DrivenCell(organism, cell, phase, handle)
        if (cell.session == null) {
            val bound = OrganicAiIntent.Internal.SessionBound(organism.id, cell.id, phase.request, handle.session)
            if (machine.send(bound) != SendResult.Accepted) {
                return abandon(driven, turn = null, CellRun.None, isConnected = false)
            }
        }
        // A turn the engine still runs started under an earlier connection; every new prompt needs a fresh check.
        val live = phase.takeIf { it.isRecovery }?.let { handle.activeTurn() }
        val run = runOf(organism, cell.id, phase.request, handle.session, live)
        var turn = live
        try {
            turn = live ?: submit(driven, run, machine) ?: return
            follow(driven, run, turn, machine)
        } catch (e: CancellationException) {
            // The profile sleeps or the effect is replaced: the engine keeps the turn, so the run stays with it.
            throw e
        } catch (e: Exception) {
            run.lost(handle.session, turn)
            throw e
        }
    }

    /** Reports the accepted [turn], waits for its end and settles it; a cell that ended meanwhile is let go. */
    private suspend fun follow(driven: DrivenCell, run: CellRun, turn: TurnId, machine: EffectScope<OrganicAiIntent>) {
        val organism = driven.organism
        val cell = driven.cell
        val phase = driven.phase
        val handle = driven.handle
        run.accepted(handle.session, turn)
        val accepted = OrganicAiIntent.Internal.TurnAccepted(organism.id, cell.id, phase.request, turn)
        if (machine.send(accepted) != SendResult.Accepted) {
            return abandon(driven, turn, run, isConnected = true)
        }
        val outcome = handle.await(turn) { pending ->
            machine.send(OrganicAiIntent.Internal.PermissionsChanged(organism.id, cell.id, phase.request, pending))
        }
        val settlement = settlement(handle, turn, outcome)
        run.settled(handle.session, turn, outcome)
        if (organism.isSandboxed && workspaces.isPaused(organism, cell.id)) {
            workspaces.awaitAvailable(organism, cell.id)
            machine.send(OrganicAiIntent.Internal.WorkspaceContinued(organism.id, cell.id, phase.request))
            return
        }
        val settled = OrganicAiIntent.Internal.TurnSettled(organism.id, cell.id, phase.request, settlement)
        val result = machine.send(settled)
        log.i {
            "organism ${organism.id.value} cell ${cell.id.value}: turn ${cell.turns} ${settlement.kind()}, " +
                "machine $result"
        }
    }

    /**
     * The worktree run of the turn: the one of a native turn that survived a restart ([live]) when the sandbox still
     * holds it, else a new one for [request] only when no native turn is running. An unmatched live turn keeps
     * its recovery barrier: a new request must never claim it without a native submission. A cell that shares
     * the organism's project has no run.
     */
    private suspend fun runOf(
        organism: Organism,
        cell: CellId,
        request: RequestId,
        session: SessionRef,
        live: TurnId?,
    ): CellRun = when {
        !organism.isSandboxed -> CellRun.None

        live == null -> workspaces.begin(organism, cell, request)

        else -> workspaces.adopt(organism, cell, session, live)
            ?: throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
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
            organism.harnesses,
        )
    }

    /**
     * Connects the harnesses, journals the organism and hands the turn's prompt and inputs to the engine; without the
     * harnesses nothing is sent. Null when the turn was settled unsent or the cell ended meanwhile.
     */
    private suspend fun submit(driven: DrivenCell, run: CellRun, machine: EffectScope<OrganicAiIntent>): TurnId? {
        val organism = driven.organism
        val cell = driven.cell
        val phase = driven.phase
        val handle = driven.handle
        val isConnected = isConnected(organism, cell.id, handle)
        // The cell may have been lysed while it waited for its connections, and whatever part of them was made must
        // go: nothing may stay connected to a dead session, whether or not every connection succeeded.
        if (organism.harnesses.isNotEmpty() && cells.isLysed(driven.key)) {
            abandon(driven, turn = null, run, isConnected = true)
            return null
        }
        if (!isConnected) {
            val broke = Settlement.Broke(Breakdown.Harnesses)
            val result = machine.send(OrganicAiIntent.Internal.TurnSettled(organism.id, cell.id, phase.request, broke))
            log.i { "organism ${organism.id.value} cell ${cell.id.value}: harnesses not connected, unsent, $result" }
            run.lost(session = null, turn = null)
            return null
        }
        journal.save(organism)
        return handle.submit(phase.request, turnPrompt(organism, cell), organism.trust, phase.inputs(organism))
    }

    /** The profile-owned inputs the turn's work carries: the goal's at genesis, the message's at a follow-up. */
    private fun CellPhase.Working.inputs(organism: Organism): List<ResourceRef> = when (val current = work) {
        Work.Genesis -> organism.attachments
        is Work.FollowUp -> current.attachments
        Work.CheckInbox, is Work.Letters -> emptyList()
    }

    /** Whether the organism's harnesses are connected to the cell's session; a failure's reason stays in the log. */
    private suspend fun isConnected(organism: Organism, id: CellId, handle: CellHandle): Boolean {
        if (organism.harnesses.isEmpty()) return true
        return try {
            harnesses.connect(handle.session, organism.harnesses)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "organism ${organism.id.value} cell ${id.value}: harnesses were not connected" }
            false
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
    private suspend fun abandon(driven: DrivenCell, turn: TurnId?, run: CellRun, isConnected: Boolean) {
        val organism = driven.organism
        val key = driven.key
        val handle = driven.handle
        log.i { "organism ${key.organism.value} cell ${key.cell.value} ended meanwhile; abandoning its session" }
        if (turn == null) {
            // Nothing was submitted: the run of a sandbox cell is rejected, since no later run closes a lysed cell's.
            run.lost(session = null, turn = null)
        } else {
            handle.cancel(turn)
            run.settled(handle.session, turn, TurnOutcome.Cancelled)
        }
        val release = cells.release(key, handle.session, ReleaseMode.Lyse)
        if (release == ReleaseOutcome.Stopped && isConnected) disconnect(organism, handle.session)
    }

    /**
     * Lets go of the organism's harness connections to the session of a cell that ended, so its dead sessions never
     * fill the connection quota of a harness. A failure is logged only: the cell is gone either way.
     */
    suspend fun disconnect(organism: Organism, session: SessionRef, isReleased: suspend () -> Boolean = { true }) {
        if (organism.harnesses.isEmpty()) return
        try {
            harnesses.disconnect(session, organism.harnesses, isReleased)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "organism ${organism.id.value}: harness connections of an ended cell were not removed" }
        }
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

/** The immutable cell turn snapshot and the session acquired for that execution. */
private data class DrivenCell(
    val organism: Organism,
    val cell: Cell,
    val phase: CellPhase.Working,
    val handle: CellHandle,
) {
    val key: CellKey get() = CellKey(organism.id, cell.id)
}
