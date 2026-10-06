package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/**
 * Original project of a known execution [workspace], including retained tasks whose checkout was removed.
 * Null means this journal has no mapping: callers must validate an ordinary project against their workspace
 * catalog. Only a restored [WorktreeState.Ready] can answer; loading or failed state is never proof that a
 * workspace is an original project.
 */
public fun WorktreeState.Ready.sourceProjectOf(workspace: WorkspaceRef): WorkspaceRef? =
    tasks.values.firstOrNull { it.executionWorkspace == workspace }?.project
