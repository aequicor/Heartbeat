package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.organicai.api.targetOf
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellResult
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRun
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellWorkspaces
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

/**
 * Sandboxes of cells through the profile's worktree machine, which owns the checkouts, their branches, native runs
 * and builds. A cell's task is keyed by [sandboxTask], so a restart or a retry finds the sandbox that was already cut
 * instead of cutting another, and its run is the one the hosted build tools of that sandbox authenticate against.
 * Every failure to prepare or begin is an [EngineException] the organism turns into a broken turn of the cell, never
 * a silent fall back to the organism's project.
 */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
@OptIn(ExperimentalCoroutinesApi::class)
internal class WorktreeCellWorkspaces(
    private val machines: MachineRegistry,
    private val local: io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces? = null,
) : CellWorkspaces {
    private val log = Log.tag("WorktreeCellWorkspaces")

    /** Fixed stripes serialize readers of one cell without retaining every cell id for the profile lifetime. */
    private val sealing = List(SEAL_STRIPES) { Mutex() }

    override suspend fun prepare(organism: Organism, cell: CellId): WorkspaceRef {
        val project = checkNotNull(organism.workspace) { "A sandbox organism needs a project" }
        val key = organism.sandboxTask(cell)
        val known = snapshot()[key]
        known?.takeIf { it.isUsable() }?.let {
            check(
                organism.baseCommit == null ||
                    (it.project == project && it.isSandbox && it.pinnedBaseCommit == organism.baseCommit),
            ) { "PinnedBaseMismatch" }
            return checkNotNull(it.executionWorkspace)
        }
        val parent = organism.cell(cell)?.parent?.takeIf { organism.baseCommit == null }?.let { parent ->
            val parentTask = awaitTask(organism.sandboxTask(parent), after = -1) { it.isUsable() || it.isFailed() }
            checkNotNull(parentTask.executionWorkspace) { "SandboxParentUnavailable" }
        }
        dispatch(
            WorktreeIntent.Public.Prepare(
                key,
                project,
                isSandbox = true,
                parentWorkspace = parent,
                isBasePinned = organism.baseCommit != null,
                baseCommit = organism.baseCommit,
            ),
        )
        log.i { "organism ${organism.id.value} cell ${cell.value}: sandbox requested" }
        val task = awaitTask(key, after = known?.revision ?: -1) { it.isUsable() || it.isFailed() }
        if (!task.isUsable()) throw unavailable("failed: ${task.failure.orEmpty()}")
        log.i { "organism ${organism.id.value} cell ${cell.value}: sandbox ready" }
        return checkNotNull(task.executionWorkspace)
    }

    override suspend fun begin(organism: Organism, cell: CellId, request: RequestId): CellRun {
        val key = organism.sandboxTask(cell)
        closeStale(key, request)
        val current = snapshot()[key]
        // A repeated drive of the same request finds its run already prepared and starts nothing.
        if (current?.isPreparedFor(request) != true) {
            var isPrepared = false
            try {
                dispatch(
                    WorktreeIntent.Public.RunStarted(
                        key,
                        request,
                        WorktreeRunKind.Coding,
                        organism.cell(cell)?.let { organism.targetOf(it) },
                    ),
                )
                val task = awaitTask(key, after = current?.revision ?: -1) {
                    it.isPreparedFor(request) || it.isFailed()
                }
                if (!task.isPreparedFor(request)) throw unavailable("run not prepared: ${task.failure.orEmpty()}")
                isPrepared = true
            } finally {
                // No prompt has been submitted: close only this matching request, including on cancellation.
                if (!isPrepared) {
                    withContext(NonCancellable) { SandboxRun(key, request).lost(session = null, turn = null) }
                }
            }
        }
        log.i { "organism ${organism.id.value} cell ${cell.value}: sandbox run prepared" }
        return SandboxRun(key, request)
    }

    override suspend fun adopt(organism: Organism, cell: CellId, session: SessionRef, turn: TurnId): CellRun? {
        val key = organism.sandboxTask(cell)
        val task = snapshot()[key]
        val run = task?.run?.takeIf { it.isOf(session, turn) } ?: return null
        if (task.phase == WorktreePhase.RecoveryRequired) {
            dispatch(WorktreeIntent.Public.RunAdopted(key, run.request, session, turn))
            val adopted = awaitTask(key, after = task.revision) { it.phase != WorktreePhase.RecoveryRequired }
            if (adopted.run?.request != run.request || adopted.phase == WorktreePhase.Failed) return null
        }
        log.i { "organism ${organism.id.value} cell ${cell.value}: native turn adopted by its sandbox run" }
        return SandboxRun(key, run.request)
    }

    override suspend fun result(organism: Organism, cell: CellId): CellResult? {
        val key = organism.sandboxTask(cell)
        val capture = sealing[key.hashCode().mod(sealing.size)]
        return capture.withLock {
            val task = snapshot()[key] ?: return@withLock null
            val branch = task.branch ?: return@withLock null
            // Readers share the current result; starting another run invalidates its previous seal.
            val existing = task.seal?.commit
            val commit = if (organism.cell(
                    cell,
                )?.parent == null && task.virtualSandbox != null && task.virtualSandbox?.resultCommit == null
            ) {
                seal(key, task, handoff = true)
            } else {
                existing ?: seal(key, task)
            }
            commit?.let {
                CellResult(
                    branch,
                    task.baseCommit,
                    it,
                    task.executionWorkspace.takeIf { task.virtualSandbox != null },
                )
            }
        }
    }

    override suspend fun branches(organism: Organism): Map<CellId, String> {
        val tasks = snapshot()
        return organism.cells.mapNotNull { cell ->
            tasks[organism.sandboxTask(cell.id)]?.branch?.let { cell.id to it }
        }.toMap()
    }

    override suspend fun isPaused(organism: Organism, cell: CellId): Boolean {
        val task = snapshot()[organism.sandboxTask(cell)] ?: return false
        return task.pendingVirtualContinuation || !sourceAvailable(task) ||
            task.virtualSandbox?.let { !it.isReady && !it.isResolving } == true
    }

    private suspend fun sourceAvailable(task: WorktreeTask): Boolean = try {
        task.executionWorkspace?.let { local?.virtual(it)?.revision() }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalStateException) {
        if (error.message !in setOf("SandboxPaused", "SandboxSourceDirty", "SandboxSourceChanged")) throw error
        log.w(error) { "Cell workspace source is unavailable" }
        false
    }

    override suspend fun awaitAvailable(organism: Organism, cell: CellId) {
        val key = organism.sandboxTask(cell)
        // A completed cell handed its sandbox to the user; a new turn (a follow-up) continues it. Any other paused
        // sandbox is rechecked once without an explicit resume: worktree mode continues it when no user decision is
        // needed (a source that moved under a loaded branch) and otherwise keeps it waiting for the user.
        val phase = snapshot()[key]?.virtualSandbox?.takeUnless { it.isReady || it.isResolving }?.phase
        if (phase != null) {
            log.i { "organism ${organism.id.value} cell ${cell.value}: rechecking its $phase sandbox" }
            // Only a handed-off sandbox is reopened, and only here, right before the cell's next turn.
            dispatch(WorktreeIntent.Public.Recheck(key, isReopen = phase == VirtualSandboxPhase.HandedOff))
        }
        machines.observe(WorktreeMachineKey).flatMapLatest { it?.state ?: flowOf(WorktreeState.Idle) }
            .first { state ->
                val task = (state as? WorktreeState.Ready)?.tasks?.get(key)
                task != null && task.virtualSandbox.let { it == null || it.isReady || it.isResolving } &&
                    sourceAvailable(
                        task,
                    )
            }
    }

    /** Asks worktree mode to capture the checkout; a refusal is recorded in the task and is not an error here. */
    private suspend fun seal(key: String, task: WorktreeTask, handoff: Boolean = false): String? {
        val operation = Uuid.random().toString()
        return try {
            dispatch(WorktreeIntent.Public.Seal(key, operation, handoff))
            awaitTask(key, after = task.revision) { it.seal?.operation == operation }.seal?.commit
        } catch (e: EngineException) {
            log.w(e) { "sandbox result could not be captured" }
            null
        }
    }

    /**
     * Closes a run of the cell that an older request left unsettled, which would otherwise keep a new one from
     * starting: a restart or a sleep cut it short. It is closed as unknown, never as completed: a turn that reached
     * the engine is settled with its identity, one that never did is rejected.
     */
    private suspend fun closeStale(key: String, request: RequestId) {
        val task = snapshot()[key] ?: return
        val stale = task.run?.takeIf { it.outcome == null && it.request != request } ?: return
        val session = stale.session
        val turn = stale.turn
        val closing = if (session != null && turn != null) {
            WorktreeIntent.Public.RunSettled(key, stale.request, session, turn, TurnOutcome.Unknown)
        } else if (session != null || task.phase == WorktreePhase.RecoveryRequired) {
            // An unaccepted recorded session may still have submitted work; only native idle inspection closes it.
            throw unavailable("submission outcome unknown")
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
        machine?.state ?: flowOf(WorktreeState.Idle)
    }.mapNotNull { state ->
        when (state) {
            is WorktreeState.Ready -> state.tasks
            WorktreeState.LoadError -> throw unavailable("the worktree journal is unavailable")
            WorktreeState.Idle, WorktreeState.Loading -> null
        }
    }

    /** The first state of the task newer than [after] that satisfies [until]; an older state is never reused. */
    private suspend fun awaitTask(key: String, after: Long, until: (WorktreeTask) -> Boolean): WorktreeTask =
        withTimeoutOrNull(WAIT_MILLIS) {
            tasks().mapNotNull { it[key] }.first { it.revision > after && until(it) }
        } ?: throw unavailable("timed out")

    /** A missing or loading journal is never mistaken for an empty restored journal. */
    private suspend fun snapshot(): Map<String, WorktreeTask> = withTimeoutOrNull(READY_MILLIS) {
        tasks().first()
    } ?: throw unavailable("the worktree journal is unavailable")

    /** Waits for the restored journal, then hands the command over; a refusal is a failure of the cell's turn. */
    private suspend fun dispatch(intent: WorktreeIntent.Public) {
        snapshot()
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
        const val SEAL_STRIPES = 32
        const val READY_MILLIS = 30_000L
        const val WAIT_MILLIS = 120_000L
        const val LOST = "NativeOutcomeUnknown"
    }
}
