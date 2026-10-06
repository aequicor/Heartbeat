package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

internal fun HarnessRunsState.Ready.startRejection(intent: HarnessRunsIntent.Public.Start): WorkflowRejection? = when {
    isSuspended -> WorkflowRejection.Unavailable
    runs.any { it.id == intent.run.id } -> WorkflowRejection.Duplicate
    runs.count { it.status == WorkflowStatus.Running } >= HarnessLimits.RUNS -> WorkflowRejection.Capacity
    !intent.run.isNewAt(intent.at) -> WorkflowRejection.InvalidRun
    else -> null
}

private fun WorkflowRun.isNewAt(at: Instant): Boolean = status == WorkflowStatus.Running &&
    cancellation == null && steps.isEmpty() && awaiting.isEmpty() && attempt == 0 && driverGeneration == 0L &&
    at >= startedAt && at < deadline && (origin != WorkflowOrigin.Agent || caller != null)

internal fun HarnessRunsState.Ready.cancelRejection(intent: HarnessRunsIntent.Public.Cancel): WorkflowRejection? {
    val run = runs.firstOrNull { it.id == intent.run && it.isVisibleTo(intent.by) }
    return when {
        run == null -> WorkflowRejection.NotFound
        run.status != WorkflowStatus.Running -> WorkflowRejection.AlreadyFinished
        else -> null
    }
}

internal fun HarnessRunsState.Ready.cancelling(id: RunId): WorkflowRun = runs.first { it.id == id }.let {
    if (it.cancellation != null) {
        it
    } else {
        it.copy(
            driverGeneration = it.driverGeneration + 1,
            cancellation = WorkflowFailure.Cancelled,
            awaiting = emptyMap(),
        )
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
        run.copy(driverGeneration = run.driverGeneration + 1, awaiting = emptyMap())
    } else {
        run.copy(awaiting = emptyMap())
    }
}

internal fun HarnessRunsState.Ready.terminal(id: RunId, status: WorkflowStatus, at: Instant): HarnessRunsState.Ready =
    copy(
        runs = runs.map { run ->
            if (run.id == id) run.copy(status = status, finishedAt = at, awaiting = emptyMap()) else run
        }.retained(at),
    )

internal fun List<WorkflowRun>.running(): List<WorkflowRun> = filter { it.status == WorkflowStatus.Running }
