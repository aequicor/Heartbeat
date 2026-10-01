package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome

/** An earlier hosted invocation cannot act for a replacement native turn. */
public fun WorktreeTask.hasActiveRun(identity: WorktreeRunIdentity?): Boolean {
    val active = run ?: return false
    if (identity == null || active.outcome != null) return false
    return active.request == identity.request && active.session == identity.session && active.turn == identity.turn
}

internal fun WorktreeTask.runStarted(command: WorktreeIntent.Public.RunStarted): WorktreeTask {
    val isPrepared = executionWorkspace != null && phase != WorktreePhase.Preparing
    val isIdle = run?.outcome != null || run == null
    val isAvailable = command.chatId == chatId && isPrepared && isIdle
    val isExpectedAction = expectedAction?.operation == command.request.value && expectedAction.kind == command.kind
    val isActionAllowed = phase != WorktreePhase.ActionWorking || isExpectedAction
    return if (isAvailable && isActionAllowed) {
        copy(
            phase = if (command.kind == WorktreeRunKind.Coding) WorktreePhase.Working else WorktreePhase.ActionWorking,
            run = WorktreeRun(command.request, command.kind),
            actionRequest = null,
            failure = null,
            isCompletionDismissed = false,
            verifiedPullRequestUrl = null,
            expectedAction = null,
        )
    } else {
        this
    }
}

internal fun WorktreeTask.runAccepted(command: WorktreeIntent.Public.RunAccepted): WorktreeTask {
    val active = pendingRun(command.chatId, command.request) ?: return this
    return if (active.canAttach(command.session, command.turn)) {
        copy(run = active.copy(session = command.session, turn = command.turn))
    } else {
        this
    }
}

internal fun WorktreeTask.runRejected(command: WorktreeIntent.Public.RunRejected): WorktreeTask {
    val active = pendingRun(command.chatId, command.request) ?: return this
    return if (active.turn == null) copy(phase = WorktreePhase.Failed, run = null, failure = command.reason) else this
}

internal fun WorktreeTask.runObservationLost(command: WorktreeIntent.Public.RunObservationLost): WorktreeTask {
    val active = pendingRun(command.chatId, command.request) ?: return this
    return if (active.canAttach(command.session, command.turn)) {
        copy(
            phase = WorktreePhase.RecoveryRequired,
            run = active.copy(session = command.session, turn = command.turn),
            failure = command.reason,
        )
    } else {
        this
    }
}

internal fun WorktreeTask.runSettled(command: WorktreeIntent.Public.RunSettled): WorktreeTask {
    val active = pendingRun(command.chatId, command.request) ?: return this
    return if (active.canAttach(command.session, command.turn)) {
        copy(run = active.copy(session = command.session, turn = command.turn, outcome = command.outcome)).settle()
    } else {
        this
    }
}

internal fun WorktreeTask.completionSignaled(command: WorktreeIntent.Public.TaskCompleteSignaled): WorktreeTask {
    val active = run?.takeIf {
        command.chatId == chatId && it.session == command.session && it.turn == command.turn
    } ?: return this
    return if (canSignalCompletion(active, command.summary)) {
        copy(
            phase = WorktreePhase.CompletionSignaled,
            run = active.copy(
                isCompletionSignaled = true,
                summary = command.summary,
                pullRequestUrl = command.pullRequestUrl,
            ),
        ).settle()
    } else {
        this
    }
}

private fun WorktreeTask.pendingRun(chat: String, request: RequestId): WorktreeRun? = run?.takeIf {
    chat == chatId && it.request == request && it.outcome == null
}

private fun WorktreeRun.canAttach(session: SessionRef, turn: TurnId): Boolean {
    val isSessionMatching = this.session == null || this.session == session
    val isTurnMatching = this.turn == null || this.turn == turn
    return isSessionMatching && isTurnMatching
}

private fun WorktreeTask.canSignalCompletion(active: WorktreeRun, summary: String): Boolean {
    val isSuccessfulOrUnsettled = active.outcome == null || active.outcome == TurnOutcome.Completed
    val isCompletionAvailable = !isCompletionDismissed && actionRequest == null
    val isStaleCodingAction = phase == WorktreePhase.ActionWorking && active.kind == WorktreeRunKind.Coding
    val isSignalValid = summary.isNotBlank() && isCompletionAvailable && !isStaleCodingAction
    return isSuccessfulOrUnsettled && isSignalValid
}
