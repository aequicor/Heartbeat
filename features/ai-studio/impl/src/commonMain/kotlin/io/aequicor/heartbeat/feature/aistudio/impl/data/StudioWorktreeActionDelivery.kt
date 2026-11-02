package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeActionRequest
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase

/** Consumes only the exact durable worktree action after the coordinator admits its execution. */
internal suspend fun StudioRunCoordinator.deliverWorktreeAction(
    host: StudioRunHost,
    worktrees: StudioWorktrees,
    id: String,
    action: WorktreeActionRequest,
    settings: RunSettings,
) = run(
    host,
    studioTurnRequest(id, action.prompt, settings, action.kind, RequestId(action.operation))
        .copy(continuationOf = action.continuationOf),
    waitForIdle = true,
    beforeExecute = {
        worktrees.send(WorktreeIntent.Public.ActionDelivered(id, action.operation))
        worktrees.await(id, failOnTaskError = false) {
            it.actionRequest == null && it.phase == WorktreePhase.ActionWorking &&
                it.expectedAction?.operation == action.operation
        }
    },
)
