package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Pure journal transition, shared by durable effects and contract tests; mismatched correlations are ignored. */
public fun WorktreeTask.transition(command: WorktreeIntent.Public): WorktreeTask = when (command) {
    is WorktreeIntent.Public.RunStarted -> runStarted(command)

    is WorktreeIntent.Public.RunAccepted -> runAccepted(command)

    is WorktreeIntent.Public.RunRejected -> runRejected(command)

    is WorktreeIntent.Public.RunObservationLost -> runObservationLost(command)

    is WorktreeIntent.Public.RunSettled -> runSettled(command)

    is WorktreeIntent.Public.TaskCompleteSignaled -> completionSignaled(command)

    is WorktreeIntent.Public.ProposeBuildPlan -> buildPlanProposed(command)

    is WorktreeIntent.Public.ApproveBuildPlan -> buildPlanApproved(command)

    is WorktreeIntent.Public.ActionDelivered -> actionDelivered(command)

    is WorktreeIntent.Public.ActionDeliveryFailed -> actionDeliveryFailed(command)

    is WorktreeIntent.Public.ChooseAction -> actionChosen(command)

    WorktreeIntent.Public.Start, WorktreeIntent.Public.RetryLoad,
    is WorktreeIntent.Public.Prepare, is WorktreeIntent.Public.TrackMainSession,
    is WorktreeIntent.Public.Recheck, is WorktreeIntent.Public.RunBuild, is WorktreeIntent.Public.CancelBuild,
    -> this
}

/** Native completion is only one half of task completion; running builds keep the decision unavailable. */
public fun WorktreeTask.settle(): WorktreeTask {
    val execution = run ?: return this
    val outcome = execution.outcome ?: return this
    val phase = when (outcome) {
        TurnOutcome.Completed -> completedPhase(execution)
        TurnOutcome.Cancelled -> WorktreePhase.Idle
        TurnOutcome.Unknown -> WorktreePhase.RecoveryRequired
        is TurnOutcome.Failed -> WorktreePhase.Failed
    }
    return copy(phase = phase)
}

private fun WorktreeTask.completedPhase(execution: WorktreeRun): WorktreePhase = when {
    builds.values.any { it.phase == WorktreeBuildPhase.Unknown } -> WorktreePhase.RecoveryRequired
    hasActiveBuilds() -> WorktreePhase.CompletionSignaled
    execution.kind != WorktreeRunKind.Coding && execution.isCompletionSignaled -> WorktreePhase.Retained
    execution.kind != WorktreeRunKind.Coding -> WorktreePhase.RecoveryRequired
    execution.isCompletionSignaled -> WorktreePhase.AwaitingDecision
    else -> WorktreePhase.Idle
}

/** Git checkout and managed workspace registration completed durably. */
public fun WorktreeTask.prepared(workspace: WorkspaceRef): WorktreeTask =
    copy(executionWorkspace = workspace, phase = WorktreePhase.Idle, failure = null)

/** Validates the pending request's execution route before a native prompt may be submitted. */
public fun WorktreeTask.runPrepared(request: RequestId): WorktreeTask = if (run?.request == request &&
    run.outcome == null
) {
    copy(run = run.copy(isPrepared = true))
} else {
    this
}

/** Safe operation failure; no filesystem paths or raw native output belong in this reason. */
public fun WorktreeTask.failed(reason: String): WorktreeTask = if (reason.startsWith("Original")) {
    needsRecovery(reason)
} else {
    copy(failure = reason, phase = WorktreePhase.Failed)
}

/** Delayed effects report failure only to their owning request; rejected builds never poison a newer run. */
public fun WorktreeTask.operationFailed(command: WorktreeIntent.Public, reason: String): WorktreeTask {
    val isStaleBuild = command is WorktreeIntent.Public.RunBuild && !hasActiveRun(command.expectedRun)
    val isStalePlan = command is WorktreeIntent.Public.ProposeBuildPlan && isStaleConfiguration(command)
    val isStaleRun = command is WorktreeIntent.Public.RunStarted && run?.request != command.request
    if (isStaleBuild || isStalePlan || isStaleRun) return this
    return if (command is WorktreeIntent.Public.RunBuild) {
        buildUpdated(
            WorktreeBuildOperation(command.operation, command.command, WorktreeBuildPhase.Failed, failure = reason),
        )
    } else {
        failed(reason)
    }
}

private fun WorktreeTask.isStaleConfiguration(command: WorktreeIntent.Public.ProposeBuildPlan): Boolean =
    command.expectedRun != null && !hasActiveRun(command.expectedRun)

/** A restart loses native outcome observation; a retained committed checkout remains available. */
public fun WorktreeTask.reconciled(isPresent: Boolean, workspace: WorkspaceRef?, restart: Boolean): WorktreeTask {
    if (!isPresent) return needsRecovery("WorktreeUnavailable")
    val task = if (executionWorkspace == null && workspace != null) prepared(workspace) else this
    val hasUnsettledTurn = task.run?.outcome == null && task.phase in setOf(
        WorktreePhase.Working,
        WorktreePhase.CompletionSignaled,
    )
    val hasUnobservedExecution =
        hasUnsettledTurn || task.phase == WorktreePhase.ActionWorking || task.actionRequest != null
    return if (restart && hasUnobservedExecution) task.needsRecovery("NativeOutcomeUnknown") else task
}

/** Trusted context advances an original-checkout build-only session without synthetic completion events. */
public fun WorktreeTask.trackingMain(command: WorktreeIntent.Public.TrackMainSession): WorktreeTask = copy(
    phase = WorktreePhase.Working,
    run = WorktreeRun(command.request, session = command.session, turn = command.turn, isPrepared = true),
    isCompletionDismissed = true,
    isIsolated = false,
)
