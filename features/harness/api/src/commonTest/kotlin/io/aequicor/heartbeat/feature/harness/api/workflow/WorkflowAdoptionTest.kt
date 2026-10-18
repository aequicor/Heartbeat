package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test

class WorkflowAdoptionTest {
    @Test
    fun `committed run is imported and fenced for cancellation in one transition`() {
        val run = workflowRun()
        val cancelled = run.copy(driverGeneration = 1, cancellation = WorkflowFailure.Cancelled)
        HarnessRunsMachineSpec.assertTransition(
            from = HarnessRunsState.Ready(),
            intent = HarnessRunsIntent.Internal.CancelStoredRun(Request, run, WorkflowViewer.User),
            to = HarnessRunsState.Ready(listOf(cancelled)),
            effects = listOf(HarnessRunsEffect.StopRun(cancelled)),
            outputs = listOf(HarnessRunsOutput.CancellationRequested(Request, run.id)),
        )
    }

    @Test
    fun `suspended machine refuses to import a committed run for cancellation`() {
        val run = workflowRun()
        HarnessRunsMachineSpec.assertTransition(
            from = HarnessRunsState.Ready(isSuspended = true),
            intent = HarnessRunsIntent.Internal.CancelStoredRun(Request, run, WorkflowViewer.User),
            to = HarnessRunsState.Ready(isSuspended = true),
            effects = emptyList(),
            outputs = listOf(HarnessRunsOutput.Rejected(Request, WorkflowRejection.Unavailable)),
        )
    }
}
