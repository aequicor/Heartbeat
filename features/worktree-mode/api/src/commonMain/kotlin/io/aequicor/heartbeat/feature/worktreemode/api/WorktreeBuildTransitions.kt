package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome

private val terminalBuildPhases = setOf(
    WorktreeBuildPhase.Completed,
    WorktreeBuildPhase.Cancelled,
    WorktreeBuildPhase.Failed,
)
private val activeBuildPhases = setOf(
    WorktreeBuildPhase.Queued,
    WorktreeBuildPhase.WaitingForResource,
    WorktreeBuildPhase.Running,
    WorktreeBuildPhase.Unknown,
)

internal fun WorktreeTask.buildPlanProposed(command: WorktreeIntent.Public.ProposeBuildPlan): WorktreeTask {
    val isOwned = command.expectedRun == null || hasActiveRun(command.expectedRun)
    if (command.chatId != chatId || hasActiveBuilds() || !isOwned) return this
    return copy(
        buildPlan = command.plan,
        isBuildApproved = true,
        buildConfigurationRevision = buildConfigurationRevision + 1,
    )
}

internal fun WorktreeTask.buildPlanApproved(command: WorktreeIntent.Public.ApproveBuildPlan): WorktreeTask = if (
    command.chatId == chatId && revision == command.revision && buildPlan != null
) {
    copy(isBuildApproved = command.isApproved)
} else {
    this
}

/** Whether a worker or queued build can still mutate the checkout. */
public fun WorktreeTask.hasActiveBuilds(): Boolean = builds.values.any { it.phase in activeBuildPhases }

/** Build progress cannot regress after starting or reaching a terminal result. */
public fun WorktreeTask.buildUpdated(build: WorktreeBuildOperation): WorktreeTask {
    val previous = builds[build.id]
    if (previous?.phase in terminalBuildPhases) return this
    val isRunningRegression = previous?.phase == WorktreeBuildPhase.Running && build.phase in setOf(
        WorktreeBuildPhase.Queued,
        WorktreeBuildPhase.WaitingForResource,
    )
    val isWaitingRegression = previous?.phase == WorktreeBuildPhase.WaitingForResource &&
        build.phase == WorktreeBuildPhase.Queued
    if (isRunningRegression || isWaitingRegression) return this
    val update = if (build.command.isBlank() && previous != null) build.copy(command = previous.command) else build
    val changed = copy(builds = builds + (update.id to update))
    return if (update.phase == WorktreeBuildPhase.Unknown) {
        changed.needsRecovery("WorkerOutcomeUnknown")
    } else {
        changed.recoveredBuild(previous, update).settle()
    }
}

private fun WorktreeTask.recoveredBuild(
    previous: WorktreeBuildOperation?,
    update: WorktreeBuildOperation,
): WorktreeTask {
    val isKnownCompletion = run?.outcome == TurnOutcome.Completed && run.kind == WorktreeRunKind.Coding
    val isStaleObservation = isKnownCompletion && failure == "NativeOutcomeUnknown" && expectedAction == null
    val hasRecovered = previous?.phase == WorktreeBuildPhase.Unknown ||
        (update.phase in terminalBuildPhases && isStaleObservation)
    return if (hasRecovered) copy(failure = null) else this
}
