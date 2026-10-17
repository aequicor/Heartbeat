package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRun
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellWorkspaces
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRun
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sandboxes of cells through the profile's worktree machine, which owns the checkouts, their branches, native runs
 * and builds. A cell's task is keyed by [sandboxTask], so a restart or a retry finds the sandbox that was already cut
 * instead of cutting another, and its run is the one the hosted build tools of that sandbox authenticate against.
 * Every failure to prepare or begin is an [EngineException] the organism turns into a broken turn of the cell, never
 * a silent fall back to the organism's project.
 */
@ContributesBinding(ProfileScope::class)
@Inject
@OptIn(ExperimentalCoroutinesApi::class)
internal class WorktreeCellWorkspaces(private val machines: MachineRegistry) : CellWorkspaces {
    private val log = Log.tag("WorktreeCellWorkspaces")

    override suspend fun prepare(organism: Organism, cell: CellId): WorkspaceRef {
        val project = checkNotNull(organism.workspace) { "A sandbox organism needs a project" }
        val key = organism.sandboxTask(cell)
        val known = tasks().first()[key]
        known?.takeIf { it.isUsable() }?.let { return checkNotNull(it.executionWorkspace) }
        dispatch(WorktreeIntent.Public.Prepare(key, project, isSandbox = true))
        log.i { "organism ${organism.id.value} cell ${cell.value}: sandbox requested" }
        val task = awaitTask(key, after = known?.revision ?: -1) { it.isUsable() || it.isFailed() }
        if (!task.isUsable()) throw unavailable("failed: ${task.failure.orEmpty()}")
        log.i { "organism ${organism.id.value} cell ${cell.value}: sandbox ready" }
        return checkNotNull(task.executionWorkspace)
    }

    override suspend fun begin(organism: Organism, cell: CellId, request: RequestId): CellRun {
        val key = organism.sandboxTask(cell)
        closeStale(key, request)
        val current = tasks().first()[key]
        // A repeated drive of the same request finds its run already prepared and starts nothing.
        if (current?.isPreparedFor(request) != true) {
            dispatch(WorktreeIntent.Public.RunStarted(key, request, WorktreeRunKind.Coding))
            val task = awaitTask(key, after = current?.revision ?: -1) { it.isPreparedFor(request) || it.isFailed() }
            if (!task.isPreparedFor(request)) throw unavailable("run not prepared: ${task.failure.orEmpty()}")
        }
        log.i { "organism ${organism.id.value} cell ${cell.value}: sandbox run prepared" }
        return SandboxRun(key, request)
    }

    override suspend fun adopt(organism: Organism, cell: CellId, session: SessionRef, turn: TurnId): CellRun? {
        val key = organism.sandboxTask(cell)
        val task = tasks().first()[key]
        val run = task?.run?.takeIf { it.isOf(session, turn) } ?: return null
        if (task.phase == WorktreePhase.RecoveryRequired) {
            dispatch(WorktreeIntent.Public.RunAdopted(key, run.request, session, turn))
            val adopted = awaitTask(key, after = task.revision) { it.phase != WorktreePhase.RecoveryRequired }
            if (adopted.run?.request != run.request || adopted.phase == WorktreePhase.Failed) return null
        }
        log.i { "organism ${organism.id.value} cell ${cell.value}: native turn adopted by its sandbox run" }
        return SandboxRun(key, run.request)
    }

    /**
     * Closes a run of the cell that an older request left unsettled, which would otherwise keep a new one from
     * starting: a restart or a sleep cut it short. It is closed as unknown, never as completed: a turn that reached
     * the engine is settled with its identity, one that never did is rejected.
     */
    private suspend fun closeStale(key: String, request: RequestId) {
        val task = tasks().first()[key] ?: return
        val stale = task.run?.takeIf { it.outcome == null && it.request != request } ?: return
        val session = stale.session
        val turn = stale.turn
        val closing = if (session != null && turn != null) {
            WorktreeIntent.Public.RunSettled(key, stale.request, session, turn, TurnOutcome.Unknown)
        } else {
            WorktreeIntent.Public.RunRejected(key, stale.request, "InterruptedBeforeAcceptance")
        }
        dispatch(closing)
        log.i { "sandbox run of an older request closed as unknown" }
        awaitTask(
            key,
            after = task.revision,
        ) { it.run.let { run -> run?.request != stale.request || run.outcome != null } }
    }

    private fun tasks(): Flow<Map<String, WorktreeTask>> = machines.observe(
        WorktreeMachineKey,
    ).flatMapLatest { machine ->
        machine?.state?.map { (it as? WorktreeState.Ready)?.tasks.orEmpty() } ?: flowOf(emptyMap())
    }

    /** The first state of the task newer than [after] that satisfies [until]; an older state is never reused. */
    private suspend fun awaitTask(key: String, after: Long, until: (WorktreeTask) -> Boolean): WorktreeTask =
        withTimeoutOrNull(WAIT_MILLIS) {
            tasks().mapNotNull { it[key] }.first { it.revision > after && until(it) }
        } ?: throw unavailable("timed out")

    /** Waits for the restored journal, then hands the command over; a refusal is a failure of the cell's turn. */
    private suspend fun dispatch(intent: WorktreeIntent.Public) {
        val machine = withTimeoutOrNull(READY_MILLIS) { machines.observe(WorktreeMachineKey).filterNotNull().first() }
        val state = machine?.let { running ->
            withTimeoutOrNull(READY_MILLIS) {
                running.state.first { it is WorktreeState.Ready || it is WorktreeState.LoadError }
            }
        }
        if (state !is WorktreeState.Ready) throw unavailable("the worktree journal is unavailable")
        val sent = machines.send(WorktreeMachineKey, intent)
        if (sent != SendResult.Accepted) throw unavailable("rejected: $sent")
    }

    /**
     * A prepared isolated checkout. Worktree mode decides whether it is a build-slot sandbox or, with sandboxes
     * turned off, a plain worktree; either keeps the cell out of the project's own checkout.
     */
    private fun WorktreeTask.isUsable(): Boolean =
        executionWorkspace != null && isIsolated && phase != WorktreePhase.Preparing && phase != WorktreePhase.Failed

    private fun WorktreeTask.isFailed(): Boolean = phase == WorktreePhase.Failed

    private fun WorktreeTask.isPreparedFor(request: RequestId): Boolean = phase == WorktreePhase.Working &&
        run?.let { it.request == request && it.outcome == null && it.isPrepared } == true

    private fun WorktreeRun.isOf(session: SessionRef, turn: TurnId): Boolean =
        kind == WorktreeRunKind.Coding && outcome == null && this.session == session && this.turn == turn

    private fun unavailable(reason: String): EngineException {
        val error = EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        log.w(error) { "cell sandbox $reason" }
        return error
    }

    /** Reports of one turn. They run to the end even when the turn's effect is cancelled, and never throw. */
    private inner class SandboxRun(private val key: String, private val request: RequestId) : CellRun {
        override suspend fun accepted(session: SessionRef, turn: TurnId) =
            report("accepted", WorktreeIntent.Public.RunAccepted(key, request, session, turn))

        override suspend fun settled(session: SessionRef, turn: TurnId, outcome: TurnOutcome) =
            report("settled", WorktreeIntent.Public.RunSettled(key, request, session, turn, outcome))

        override suspend fun lost(session: SessionRef?, turn: TurnId?) = report(
            "lost",
            when {
                session != null && turn != null ->
                    WorktreeIntent.Public.RunObservationLost(key, request, session, turn, LOST)

                session != null -> WorktreeIntent.Public.RunSubmissionObservationLost(key, request, session, LOST)

                else -> WorktreeIntent.Public.RunRejected(key, request, "RequestRejected")
            },
        )

        private suspend fun report(what: String, intent: WorktreeIntent.Public) {
            try {
                withContext(NonCancellable) { dispatch(intent) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "sandbox run $what could not be reported; the next run of the cell closes it" }
            }
        }
    }

    private companion object {
        const val READY_MILLIS = 30_000L
        const val WAIT_MILLIS = 120_000L
        const val LOST = "NativeOutcomeUnknown"
    }
}
