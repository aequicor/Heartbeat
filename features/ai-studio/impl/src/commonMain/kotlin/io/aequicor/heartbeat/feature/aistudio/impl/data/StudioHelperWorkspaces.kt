package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import kotlinx.coroutines.flow.first

/** Persists the fork intent before provisioning, so recovery keeps the same parent relationship. */
internal suspend fun saveHelperWorkspace(
    record: StudioChatRecord,
    workspaces: LocalWorkspaces,
    worktrees: StudioWorktrees,
    saveConversation: suspend (StudioChatRecord) -> Unit,
) {
    check(record.helper != null && record.ref == null && record.lastRunRequest == null)
    val parent = record.executionWorkspace
    if (parent != null && workspaces.virtual(parent) != null) {
        val task = worktrees.tasks().first().values.single { it.executionWorkspace == parent }
        val pending = record.copy(
            worktreeTaskId = record.id,
            isSandbox = true,
            projectId = task.project.value,
            executionWorkspace = null,
            virtualParentWorkspace = parent,
        )
        saveConversation(pending)
        worktrees.send(
            WorktreeIntent.Public.Prepare(record.id, task.project, isSandbox = true, parentWorkspace = parent),
        )
        val prepared = worktrees.await(record.id) { it.executionWorkspace != null }
        saveConversation(pending.copy(executionWorkspace = prepared.executionWorkspace))
    } else {
        saveConversation(record)
    }
}
