package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Validates physical or virtual execution access while retaining the chat's opaque workspace identity. */
internal suspend fun StudioChatRecord.validatedExecutionWorkspace(
    workspaces: LocalWorkspaces,
    isLocalWorkspaceSupported: Boolean,
): WorkspaceRef? {
    check(worktreeTaskId == null || executionWorkspace != null) { "The conversation worktree is not ready" }
    return resolvedExecutionWorkspace()?.also { ref ->
        check(isLocalWorkspaceSupported) { "Choose a model with local project access" }
        checkNotNull(workspaces.executionDirectory(ref)) { "The project folder is unavailable" }
    }
}
