package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

internal fun HarnessRunsState.Ready.startRejection(intent: HarnessRunsIntent.Internal.StartRun): WorkflowRejection? =
    when {
        isSuspended -> WorkflowRejection.Unavailable
        runs.any { it.id == intent.run.id } -> WorkflowRejection.Duplicate
        runs.count { it.status == WorkflowStatus.Running } >= HarnessLimits.RUNS -> WorkflowRejection.Capacity
        !intent.run.isNewAt(intent.at) -> WorkflowRejection.InvalidRun
        else -> null
    }

private fun WorkflowRun.isNewAt(at: Instant): Boolean = status == WorkflowStatus.Running &&
    cancellation == null && steps.isEmpty() && awaiting.isEmpty() && organismRequests.isEmpty() && attempt == 0 &&
    driverGeneration == 0L && at >= startedAt && at < deadline && (origin != WorkflowOrigin.Agent || caller != null)

internal fun HarnessRunsState.Ready.cancelRejection(intent: HarnessRunsIntent.Internal.CancelRun): WorkflowRejection? {
    val run = runs.firstOrNull { it.id == intent.run && it.isVisibleTo(intent.by) }
    return when {
        run == null -> WorkflowRejection.NotFound
        run.status != WorkflowStatus.Running -> WorkflowRejection.AlreadyFinished
        else -> null
    }
}

/**
 * Only the user answers, and only a request the run's current driver shows: a request that is gone, already answered
 * or of another run is NotFound, so a probing caller learns nothing about which requests exist.
 */
internal fun HarnessRunsState.Ready.organismDecisionRejection(
    intent: HarnessRunsIntent.Internal.DecideOrganismPermission,
): WorkflowRejection? {
    val run = runs.firstOrNull { it.id == intent.run }
    return when {
        intent.by != WorkflowViewer.User || run == null -> WorkflowRejection.NotFound
        run.status != WorkflowStatus.Running -> WorkflowRejection.AlreadyFinished
        isSuspended || run.cancellation != null -> WorkflowRejection.Unavailable
        run.organismRequests.none { it.asks(intent) } -> WorkflowRejection.NotFound
        else -> null
    }
}

private fun OrganismPermission.asks(intent: HarnessRunsIntent.Internal.DecideOrganismPermission): Boolean =
    step == intent.step && cell == intent.cell && request.accepts(intent.decision)

internal fun HarnessRunsState.Ready.cancelling(id: RunId): WorkflowRun = runs.first { it.id == id }.let {
    if (it.cancellation != null) {
        it
    } else {
        it.copy(driverGeneration = it.driverGeneration + 1, cancellation = WorkflowFailure.Cancelled)
            .withoutLiveRequests()
    }
}

internal fun HarnessRunsState.Ready.replace(run: WorkflowRun): HarnessRunsState.Ready =
    copy(runs = runs.map { if (it.id == run.id) run else it })

internal fun HarnessRunsState.Ready.current(id: RunId, generation: Long): WorkflowRun? =
    runs.firstOrNull { it.id == id && it.driverGeneration == generation && it.status == WorkflowStatus.Running }

internal fun HarnessRunsState.Ready.executing(id: RunId, generation: Long): WorkflowRun? =
    if (isSuspended) null else current(id, generation)?.takeIf { it.cancellation == null }

internal fun List<WorkflowRun>.retained(at: Instant): List<WorkflowRun> {
    val kept = filter { it.status != WorkflowStatus.Running && checkNotNull(it.finishedAt) >= at - 30.days }
        .groupBy { it.harness }
        .values.flatMap { entries -> entries.sortedByDescending { it.finishedAt }.take(20) }
        .map { it.id }.toSet()
    return filter { it.status == WorkflowStatus.Running || it.id in kept }
}

internal fun List<WorkflowRun>.resuming(): List<WorkflowRun> = map { run ->
    if (run.status == WorkflowStatus.Running) {
        run.copy(driverGeneration = run.driverGeneration + 1).withoutLiveRequests()
    } else {
        run.withoutLiveRequests()
    }
}

internal fun HarnessRunsState.Ready.terminal(id: RunId, status: WorkflowStatus, at: Instant): HarnessRunsState.Ready =
    copy(
        runs = runs.map { run ->
            if (run.id == id) run.copy(status = status, finishedAt = at).withoutLiveRequests() else run
        }.retained(at),
    )

internal fun List<WorkflowRun>.running(): List<WorkflowRun> = filter { it.status == WorkflowStatus.Running }
