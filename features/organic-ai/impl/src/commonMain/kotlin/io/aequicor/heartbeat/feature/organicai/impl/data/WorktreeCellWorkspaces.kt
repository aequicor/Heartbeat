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
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellWorkspaces
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sandboxes of cells through the profile's worktree machine, which owns the checkouts, their branches and runs. A
 * cell's task is keyed by [sandboxTask], so a restart or a retry finds the sandbox that was already cut instead of
 * cutting another. Every failure is an [EngineException] the organism turns into a broken turn of the cell, never a
 * silent fall back to the organism's project.
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
        request(WorktreeIntent.Public.Prepare(key, project, isSandbox = true))
        log.i { "organism ${organism.id.value} cell ${cell.value}: sandbox requested" }
        val task = awaitOutcome(key, after = known?.revision ?: -1)
        if (!task.isUsable()) throw unavailable("failed: ${task.failure.orEmpty()}")
        log.i { "organism ${organism.id.value} cell ${cell.value}: sandbox ready" }
        return checkNotNull(task.executionWorkspace)
    }

    /** Waits for the restored journal, then hands the command over; a refusal is a failure of the cell's turn. */
    private suspend fun request(intent: WorktreeIntent.Public) {
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

    /** The first state of the task newer than [after] that is prepared or failed; an older failure is never reused. */
    private suspend fun awaitOutcome(key: String, after: Long): WorktreeTask = withTimeoutOrNull(PREPARE_MILLIS) {
        tasks().mapNotNull { it[key] }.first { it.revision > after && (it.isUsable() || it.isFailed()) }
    } ?: throw unavailable("timed out")

    private fun tasks() = machines.observe(WorktreeMachineKey).flatMapLatest { machine ->
        machine?.state?.map { (it as? WorktreeState.Ready)?.tasks.orEmpty() } ?: flowOf(emptyMap())
    }

    /**
     * A prepared isolated checkout. Worktree mode decides whether it is a build-slot sandbox or, with sandboxes
     * turned off, a plain worktree; either keeps the cell out of the project's own checkout.
     */
    private fun WorktreeTask.isUsable(): Boolean =
        executionWorkspace != null && isIsolated && phase != WorktreePhase.Preparing && phase != WorktreePhase.Failed

    private fun WorktreeTask.isFailed(): Boolean = phase == WorktreePhase.Failed

    private fun unavailable(reason: String): EngineException {
        val error = EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        log.w(error) { "cell sandbox $reason" }
        return error
    }

    private companion object {
        const val READY_MILLIS = 30_000L
        const val PREPARE_MILLIS = 120_000L
    }
}
