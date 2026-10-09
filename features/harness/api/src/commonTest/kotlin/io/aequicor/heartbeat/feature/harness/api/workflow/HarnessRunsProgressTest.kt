package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse

class HarnessRunsProgressTest {
    private val spec = HarnessRunsMachineSpec
    private val run = workflowRun()
    private val ready = HarnessRunsState.Ready(listOf(run))
    private val step = preparedStep().copy(helper = HelperId("helper"), request = Request)

    @Test
    fun `durable terminal receipt repairs feedback lost to cancellation or suspension exactly once`() {
        val status = WorkflowStatus.Completed(JsonPrimitive("durable result"))
        val saved = run.copy(status = status, finishedAt = Now)
        val projected = run.copy(driverGeneration = 2, cancellation = WorkflowFailure.Cancelled)
        val state = HarnessRunsState.Ready(listOf(projected), isSuspended = true)
        val done = HarnessRunsState.Ready(listOf(saved), isSuspended = true)
        spec.assertTransition(
            state, HarnessRunsIntent.Internal.TerminalRestored(saved, 2), done,
            outputs = listOf(HarnessRunsOutput.RunFinished(saved.id, status)),
        )
        spec.assertIgnored(done, HarnessRunsIntent.Internal.TerminalRestored(saved, 2))
        spec.assertIgnored(state, HarnessRunsIntent.Internal.TerminalRestored(saved, 1))
        spec.assertIgnored(state, HarnessRunsIntent.Internal.TerminalRestored(run, 2))
        spec.assertIgnored(state, HarnessRunsIntent.Internal.TerminalRestored(saved.copy(driverGeneration = 3), 2))
        val changed = saved.copy(input = JsonObject(mapOf("changed" to JsonPrimitive(true))))
        spec.assertIgnored(state, HarnessRunsIntent.Internal.TerminalRestored(changed, 2))
    }

    @Test
    fun `preparation native observation terminal result and typed failure preserve exact correlation`() {
        val prepared = run.copy(steps = listOf(step))
        val first = HarnessRunsState.Ready(listOf(prepared))
        spec.assertTransition(ready, HarnessRunsIntent.Internal.StepStarted(run.id, 0, step), first)
        val observed = step.copy(phase = StepPhase.Running, session = Caller, turn = TurnId("turn"))
        val active = HarnessRunsState.Ready(listOf(run.copy(steps = listOf(observed))))
        spec.assertTransition(first, HarnessRunsIntent.Internal.StepStarted(run.id, 0, observed), active)
        val completed = observed.copy(phase = StepPhase.Completed, result = JsonPrimitive(SECRET))
        val done = HarnessRunsState.Ready(listOf(run.copy(steps = listOf(completed))))
        val transition = spec.assertTransition(
            active,
            HarnessRunsIntent.Internal.StepFinished(run.id, 0, completed),
            done,
        )
        assertFalse(transition.isStateChange)
        spec.assertIgnored(done, HarnessRunsIntent.Internal.StepStarted(run.id, 0, observed))
        spec.assertIgnored(done, HarnessRunsIntent.Internal.StepFinished(run.id, 0, completed))
        val failed = observed.copy(phase = StepPhase.Failed, failure = WorkflowFailure.Diverged)
        spec.assertTransition(
            active,
            HarnessRunsIntent.Internal.StepFailed(run.id, 0, failed),
            HarnessRunsState.Ready(listOf(run.copy(steps = listOf(failed)))),
        )
        spec.assertIgnored(ready, HarnessRunsIntent.Internal.StepFinished(run.id, 0, completed))
        spec.assertIgnored(active, HarnessRunsIntent.Internal.StepStarted(run.id, 0, completed))
        spec.assertIgnored(active, HarnessRunsIntent.Internal.StepFinished(run.id, 0, failed))
        spec.assertIgnored(active, HarnessRunsIntent.Internal.StepFailed(run.id, 0, completed))
    }

    @Test
    fun `stale drivers terminal runs cancellation and suspension cannot mutate steps or permissions`() {
        val terminal = run.copy(status = WorkflowStatus.Failed(WorkflowFailure.Cancelled), finishedAt = Now)
        val states = listOf(
            HarnessRunsState.Ready(listOf(run.copy(driverGeneration = 1))),
            HarnessRunsState.Ready(listOf(terminal)),
            HarnessRunsState.Ready(listOf(run.copy(cancellation = WorkflowFailure.Cancelled))),
            ready.copy(isSuspended = true),
            HarnessRunsState.Ready(),
        )
        val intents = listOf(
            HarnessRunsIntent.Internal.StepStarted(run.id, 0, step),
            HarnessRunsIntent.Internal.StepFinished(
                run.id,
                0,
                step.copy(phase = StepPhase.Completed, result = JsonPrimitive("ok")),
            ),
            HarnessRunsIntent.Internal.StepFailed(
                run.id,
                0,
                step.copy(phase = StepPhase.Failed, failure = WorkflowFailure.Error),
            ),
            HarnessRunsIntent.Internal.PermissionsChanged(run.id, 0, emptyMap()),
            HarnessRunsIntent.Internal.Recovered(run.id, 0, 1),
            HarnessRunsIntent.Internal.Finished(run.id, 0, WorkflowStatus.Completed(JsonPrimitive("ok")), Now),
        )
        for (state in states) for (intent in intents) spec.assertIgnored(state, intent)
    }

    @Test
    fun `recovery requires a distinct request and exactly the next attempt without replacing memoized result`() {
        val observed = step.copy(phase = StepPhase.Running, session = Caller, turn = TurnId("turn"))
        val state = HarnessRunsState.Ready(listOf(run.copy(steps = listOf(observed))))
        val retry = step.copy(attempt = 1, request = RequestId("recovery"))
        spec.assertTransition(
            state,
            HarnessRunsIntent.Internal.StepStarted(run.id, 0, retry),
            HarnessRunsState.Ready(listOf(run.copy(steps = listOf(retry)))),
        )
        val invalid = listOf(
            retry.copy(request = step.request),
            retry.copy(attempt = 2),
            retry.copy(promptSha = "b".repeat(64)),
            observed.copy(request = RequestId("wrong")),
            observed.copy(helper = HelperId("wrong")),
            observed.copy(session = Caller.copy(nativeId = "wrong")),
            observed.copy(turn = TurnId("wrong")),
            step,
        )
        for (candidate in invalid) {
            spec.assertIgnored(
                state,
                HarnessRunsIntent.Internal.StepStarted(run.id, 0, candidate),
            )
        }
        val completed = observed.copy(phase = StepPhase.Completed, result = JsonPrimitive("ok"))
        spec.assertIgnored(
            HarnessRunsState.Ready(listOf(run.copy(steps = listOf(completed)))),
            HarnessRunsIntent.Internal.StepStarted(run.id, 0, retry),
        )
    }

    @Test
    fun `step limit cannot evict earlier memoized values`() {
        val steps = (0 until HarnessLimits.STEPS).map {
            preparedStep().copy(key = StepKey("s$it"), phase = StepPhase.Completed, result = JsonPrimitive(it))
        }
        val state = HarnessRunsState.Ready(listOf(run.copy(steps = steps)))
        spec.assertIgnored(state, HarnessRunsIntent.Internal.StepStarted(run.id, 0, step))
        spec.assertIgnored(ready, HarnessRunsIntent.Internal.StepStarted(run.id, 0, step.copy(attempt = 1)))
    }

    @Test
    fun `permissions and recovery attempt update without restarting other effects`() {
        val permissions = mapOf(
            Caller to emptyList<io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest>(),
        )
        val projected = HarnessRunsState.Ready(listOf(run.copy(awaiting = permissions)))
        val result = spec.assertTransition(
            ready,
            HarnessRunsIntent.Internal.PermissionsChanged(run.id, 0, permissions),
            projected,
        )
        assertFalse(result.isStateChange)
        val next = run.copy(attempt = 1, driverGeneration = 1)
        val recovered = HarnessRunsState.Ready(listOf(next))
        spec.assertTransition(
            ready,
            HarnessRunsIntent.Internal.Recovered(run.id, 1, 1),
            recovered,
            listOf(HarnessRunsEffect.Drive(listOf(next))),
        )
        spec.assertIgnored(recovered, HarnessRunsIntent.Internal.Recovered(run.id, 1, 1))
        spec.assertIgnored(ready, HarnessRunsIntent.Internal.Recovered(run.id, 1, 2))
        spec.assertIgnored(recovered, HarnessRunsIntent.Internal.StepStarted(run.id, 0, step))
    }

    @Test
    fun `terminal proof publishes once and leaves other runs executing`() {
        val other = workflowRun("wf_other")
        val state = ready.copy(runs = listOf(run, other))
        val status = WorkflowStatus.Completed(JsonPrimitive(SECRET))
        val done = HarnessRunsState.Ready(listOf(run.copy(status = status, finishedAt = Now), other))
        spec.assertTransition(
            state,
            HarnessRunsIntent.Internal.Finished(run.id, 0, status, Now),
            done,
            outputs = listOf(HarnessRunsOutput.RunFinished(run.id, status)),
        )
        spec.assertIgnored(done, HarnessRunsIntent.Internal.Finished(run.id, 0, status, Now))
        spec.assertIgnored(done, HarnessRunsIntent.Internal.Failed(run.id, 0, WorkflowFailure.Error, Now))
        spec.assertIgnored(ready, HarnessRunsIntent.Internal.Failed(run.id, 1, WorkflowFailure.Error, Now))
        val failure = WorkflowStatus.Failed(WorkflowFailure.Timeout)
        spec.assertTransition(
            state,
            HarnessRunsIntent.Internal.Failed(run.id, 0, WorkflowFailure.Timeout, Now),
            HarnessRunsState.Ready(listOf(run.copy(status = failure, finishedAt = Now), other)),
            outputs = listOf(HarnessRunsOutput.RunFinished(run.id, failure)),
        )
    }
}
