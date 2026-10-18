package io.aequicor.heartbeat.feature.harness.api.workflow

/** Read-only candidate for one atomic cancellation; never publish the candidate without its cancellation fence. */
internal fun HarnessRunsState.Ready.withStoredRun(
    intent: HarnessRunsIntent.Internal.CancelStoredRun,
): HarnessRunsState.Ready = if (runs.any { it.id == intent.run.id }) this else copy(runs = runs + intent.run)

internal fun HarnessRunsIntent.Internal.CancelStoredRun.cancellation(): HarnessRunsIntent.Internal.CancelRun =
    HarnessRunsIntent.Internal.CancelRun(requestId, run.id, by)

internal fun HarnessRunsState.Ready.storedCancellationRejection(
    intent: HarnessRunsIntent.Internal.CancelStoredRun,
): WorkflowRejection? = withStoredRun(intent).cancelRejection(intent.cancellation())

internal fun HarnessRunsState.Ready.storedCancellation(
    intent: HarnessRunsIntent.Internal.CancelStoredRun,
): WorkflowRun = withStoredRun(intent).cancelling(intent.run.id)
