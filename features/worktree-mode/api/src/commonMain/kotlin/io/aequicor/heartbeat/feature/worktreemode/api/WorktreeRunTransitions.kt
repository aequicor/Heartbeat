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
            closedSubmission = null,
            actionRequest = null,
            pendingVirtualContinuation = false,
            virtualContinuations = continuedRequests(command.request),
            failure = null,
            isCompletionDismissed = false,
            verifiedPullRequestUrl = null,
            expectedAction = null,
            // A resumed checkout produces a new result, even when the same cell owns both turns.
            seal = null,
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
    return if (active.turn == null && active.session == null && phase != WorktreePhase.RecoveryRequired) {
        copy(phase = WorktreePhase.Failed, run = null, failure = command.reason, closedSubmission = command.request)
    } else {
        this
    }
}

internal fun WorktreeTask.runSubmissionObservationLost(
    command: WorktreeIntent.Public.RunSubmissionObservationLost,
): WorktreeTask {
    val active = pendingRun(command.chatId, command.request) ?: return this
    val isSessionMatching = active.session == null || active.session == command.session
    return if (active.turn == null && isSessionMatching) {
        copy(
            phase = WorktreePhase.RecoveryRequired,
            run = active.copy(session = command.session),
            failure = command.reason,
        )
    } else {
        this
    }
}

internal fun WorktreeTask.runSubmissionReconciled(
    command: WorktreeIntent.Public.RunSubmissionReconciled,
): WorktreeTask {
    val active = pendingRun(command.chatId, command.request) ?: return this
    return if (phase == WorktreePhase.RecoveryRequired && active.turn == null && active.session == command.session) {
        copy(
            phase = WorktreePhase.Failed,
            run = null,
            failure = "NativeOutcomeUnknown",
            closedSubmission = command.request,
        )
    } else {
        this
    }
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

internal fun WorktreeTask.runAdopted(command: WorktreeIntent.Public.RunAdopted): WorktreeTask {
    val active = pendingRun(command.chatId, command.request) ?: return this
    val isRecorded = active.session == command.session && active.turn == command.turn
    return if (phase == WorktreePhase.RecoveryRequired && active.kind == WorktreeRunKind.Coding && isRecorded) {
        copy(
            phase = if (active.isCompletionSignaled) WorktreePhase.CompletionSignaled else WorktreePhase.Working,
            failure = null,
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

/** Follows the logical helper request across fresh accepted turns without replaying the original. */
private fun WorktreeTask.continuedRequests(request: RequestId): Map<String, String> {
    val previous = run?.request?.takeIf { it in virtualInterruptedRequests } ?: return virtualContinuations
    return virtualContinuations.mapValues { (_, value) ->
        if (value == previous.value) request.value else value
    } + (previous.value to request.value)
}
