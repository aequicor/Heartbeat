package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismCommit
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Worktree-mode owns every Git operation. The zygote's deterministic task captures the stage base durably before
 * conception, so a retry never reads a different HEAD. Pinned physical sandboxes use the existing snapshot build
 * queue. No agent session is opened here, and failure never yields the original checkout as a fallback.
 */
@Inject
@OptIn(ExperimentalCoroutinesApi::class)
internal class WorkflowOrganismWorkspaces(private val machines: MachineRegistry) {
    private val log = Log.tag("WorkflowOrganismWorkspaces")

    suspend fun prepare(organism: WorkflowOrganism, project: WorkspaceRef, base: String?): String? = guarded {
        val key = "organism.${organism.id}.${CellId.ZYGOTE.value}"
        val before = task(key)
        if (before != null) {
            check(before.project == project && before.isSandbox && before.pinnedBaseCommit != null)
            check(base == null || before.pinnedBaseCommit == base)
            if (before.phase != WorktreePhase.Preparing) {
                check(before.executionWorkspace != null && before.phase != WorktreePhase.Failed)
                return@guarded before.pinnedBaseCommit
            }
        }
        send(WorktreeIntent.Public.Prepare(key, project, isSandbox = true, isBasePinned = true, baseCommit = base))
        val ready = await(key) { it.phase != WorktreePhase.Preparing }
        check(ready.executionWorkspace != null && ready.phase != WorktreePhase.Failed && ready.isSandbox)
        check(ready.project == project && (base == null || ready.pinnedBaseCommit == base))
        checkNotNull(ready.pinnedBaseCommit)
    }

    /** Durable native-run records must agree with organic AI's in-memory release, including after restart. */
    suspend fun isReleased(organism: Organism): Boolean = guarded {
        val tasks = checkNotNull(withTimeoutOrNull(READY_MILLIS) { states().first().tasks })
        organism.cells.all { cell ->
            val task = tasks[organism.sandboxTask(cell.id)]
            when {
                task == null -> cell.session == null
                task.phase == WorktreePhase.RecoveryRequired -> false
                task.run == null -> cell.session == null || task.closedSubmission != null
                else -> task.run?.outcome != null
            }
        }
    } == true

    suspend fun output(organism: Organism): OrganismCommit? = guarded {
        val key = organism.sandboxTask(CellId.ZYGOTE)
        val before = checkNotNull(task(key))
        val base = checkNotNull(organism.baseCommit)
        check(before.pinnedBaseCommit == base && before.project == organism.workspace && before.isSandbox)
        // The same operation revalidates its result ref after a restart, rather than trusting a saved SHA string.
        val operation = "workflow_${organism.id.value}"
        send(WorktreeIntent.Public.Seal(key, operation))
        val sealed = await(key) { it.sealRevision > before.sealRevision && it.seal?.operation == operation }
        val proof = checkNotNull(sealed.seal)
        check(proof.failure == null && sealed.pinnedBaseCommit == base)
        OrganismCommit(checkNotNull(sealed.branch), base, checkNotNull(proof.commit))
    }

    private suspend fun task(key: String): WorktreeTask? = withTimeoutOrNull(READY_MILLIS) {
        states().first().tasks[key]
    } ?: run {
        // A ready empty journal and an unavailable journal must be distinguished before sending Prepare.
        val state = machines.find(WorktreeMachineKey)?.state?.value
        check(state is WorktreeState.Ready) { "WorktreeUnavailable" }
        state.tasks[key]
    }

    private suspend fun await(key: String, until: (WorktreeTask) -> Boolean): WorktreeTask = checkNotNull(
        withTimeoutOrNull(WAIT_MILLIS) { states().mapNotNull { it.tasks[key] }.first(until) },
    ) { "WorktreeOperationUnconfirmed" }

    private fun states() = machines.observe(WorktreeMachineKey)
        .flatMapLatest { it?.state ?: flowOf(WorktreeState.Idle) }
        .mapNotNull {
            check(it != WorktreeState.LoadError) { "WorktreeJournalUnavailable" }
            it as? WorktreeState.Ready
        }

    private suspend fun send(command: WorktreeIntent.Public) {
        check(machines.send(WorktreeMachineKey, command) == SendResult.Accepted) { "WorktreeRequestRejected" }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(harnessScriptFailure(error)) { "Workflow sandbox execution proof unavailable" }
        null
    }

    private companion object {
        const val READY_MILLIS = 30_000L
        const val WAIT_MILLIS = 120_000L
    }
}
