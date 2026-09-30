package io.aequicor.heartbeat.feature.worktreemode.api

internal fun WorktreeTask.actionDelivered(command: WorktreeIntent.Public.ActionDelivered): WorktreeTask = if (
    command.chatId == chatId && actionRequest?.operation == command.operation
) {
    copy(phase = WorktreePhase.ActionWorking, actionRequest = null)
} else {
    this
}

internal fun WorktreeTask.actionDeliveryFailed(command: WorktreeIntent.Public.ActionDeliveryFailed): WorktreeTask {
    val isExpected = actionRequest?.operation == command.operation || expectedAction?.operation == command.operation
    val isNativeRunning = run?.request?.value == command.operation && run.outcome == null
    return if (command.chatId == chatId && isExpected && !isNativeRunning) needsRecovery(command.reason) else this
}

internal fun WorktreeTask.actionChosen(command: WorktreeIntent.Public.ChooseAction): WorktreeTask = if (
    command.chatId == chatId && phase == WorktreePhase.AwaitingDecision
) {
    when {
        command.action == WorktreeAction.Leave -> copy(phase = WorktreePhase.Retained, isCompletionDismissed = true)

        command.action == WorktreeAction.Refine && command.refinement.isNullOrBlank() -> copy(
            phase = WorktreePhase.Idle,
            run = run?.copy(isCompletionSignaled = false, summary = null),
            isCompletionDismissed = true,
        )

        else -> this
    }
} else {
    this
}

/** A completion action may be claimed once while the checkout has no queued or executing build. */
public fun WorktreeTask.canChooseAction(): Boolean =
    phase == WorktreePhase.AwaitingDecision && !hasActiveBuilds() && actionRequest == null

/** Claims delivery before any external action preflight; a second click cannot create a second operation. */
public fun WorktreeTask.claimAction(): WorktreeTask = if (canChooseAction()) {
    copy(phase = WorktreePhase.ActionWorking, verifiedPullRequestUrl = null)
} else {
    this
}

/** A persisted action prompt is ready for profile-owned delivery. */
public fun WorktreeTask.actionPrepared(request: WorktreeActionRequest): WorktreeTask = copy(
    phase = WorktreePhase.ActionWorking,
    actionRequest = request,
    expectedAction = WorktreeExpectedAction(request.operation, request.kind),
)

/** Unknown external results require explicit recovery, never an automatic native prompt replay. */
public fun WorktreeTask.needsRecovery(reason: String): WorktreeTask =
    copy(phase = WorktreePhase.RecoveryRequired, actionRequest = null, failure = reason)

/** Exposes only an authoritative checked pull request URL, never a model-supplied candidate. */
public fun WorktreeTask.actionVerified(url: String?): WorktreeTask =
    copy(verifiedPullRequestUrl = url, failure = null).settle()

/** Explicitly checked source checkout allows returning to the prior completion decision. */
public fun WorktreeTask.preflightRecovered(): WorktreeTask = settle().copy(failure = null, expectedAction = null)
