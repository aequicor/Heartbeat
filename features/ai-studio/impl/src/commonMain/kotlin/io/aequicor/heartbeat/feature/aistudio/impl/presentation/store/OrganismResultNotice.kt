package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.isSandboxed
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.organicai.api.zygote
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** A host-owned result projection, updated when either completion or its later capture arrives. */
internal fun organismResultNotices(
    organisms: Flow<Map<String, Organism>>,
    tasks: Flow<Map<String, WorktreeTask>>,
): Flow<Map<String, MessageUi.OrganismResult>> = combine(organisms, tasks) { known, worktrees ->
    known.mapNotNull { (chat, organism) ->
        organism.resultNotice(worktrees)?.let { chat to it }
    }.toMap()
}.distinctUntilChanged()

/**
 * Only a completed sandbox's current root result is presented. Worktree mode invalidates the previous seal under
 * its capture barrier before preparing another run. Recovery may adopt an older request, so matching relies on
 * the saved session and checkout instead of rebuilding request ids from the organism's generation.
 */
private fun Organism.resultNotice(tasks: Map<String, WorktreeTask>): MessageUi.OrganismResult? {
    if (!isSandboxed || status !is OrganismStatus.Completed) return null
    val root = zygote
    return tasks[sandboxTask(root.id)]?.takeIf { it.isResultOf(root) }?.toResultNotice(id.value)
}

private fun WorktreeTask.isResultOf(root: Cell): Boolean {
    val active = run ?: return false
    return active.outcome != null && root.session != null && active.session == root.session &&
        root.workspace != null && executionWorkspace == root.workspace && isIsolated
}

private fun WorktreeTask.toResultNotice(organism: String): MessageUi.OrganismResult? {
    val captured = seal ?: return null
    val resultBranch = branch ?: return null
    val base = baseCommit ?: return null
    val commit = captured.commit ?: return null
    return MessageUi.OrganismResult(
        id = "organism-result:$organism:${captured.operation}",
        branch = resultBranch,
        base = base,
        commit = commit,
    )
}
