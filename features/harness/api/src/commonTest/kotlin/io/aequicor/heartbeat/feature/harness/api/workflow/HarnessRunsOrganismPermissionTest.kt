package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessRunsOrganismPermissionTest {
    private val spec = HarnessRunsMachineSpec
    private val asking = OrganismPermission(StepKey("s0"), "c1", "Builder", request())
    private val run = workflowRun().copy(organismRequests = listOf(asking))
    private val ready = HarnessRunsState.Ready(listOf(run))
    private val allow = PermissionDecision(TurnId("turn"), PermissionRequestId("req"), PermissionOptionId("allow"))
    private val decide = HarnessRunsIntent.Internal.DecideOrganismPermission(
        Request,
        run.id,
        asking.step,
        asking.cell,
        allow,
        WorkflowViewer.User,
    )

    @Test
    fun `navigation counts only live requests of active runs and groups by harness`() {
        val other = run.copy(id = RunId("wf_other"))
        assertEquals(mapOf(run.harness to 2), HarnessRunsState.Ready(listOf(run, other)).organismPermissionCounts())
        assertEquals(emptyMap(), ready.copy(isSuspended = true).organismPermissionCounts())
        assertEquals(emptyMap(), HarnessRunsState.Loading().organismPermissionCounts())
        assertEquals(emptyMap(), ready.copy(runs = listOf(run.withoutLiveRequests())).organismPermissionCounts())
        val cancelled = run.copy(cancellation = WorkflowFailure.Cancelled)
        val completed = other.copy(status = WorkflowStatus.Completed(JsonPrimitive("ok")), finishedAt = Now)
        assertEquals(emptyMap(), HarnessRunsState.Ready(listOf(cancelled, completed)).organismPermissionCounts())
    }

    @Test
    fun `the current driver replaces the requests of its organisms and a stale one cannot`() {
        val quiet = HarnessRunsState.Ready(listOf(workflowRun()))
        val changed = HarnessRunsIntent.Internal.OrganismPermissionsChanged(run.id, 0, listOf(asking))
        val result = spec.assertTransition(quiet, changed, ready)
        assertFalse(result.isStateChange)
        spec.assertTransition(ready, changed.copy(requests = emptyList()), quiet)
        val stale = listOf(
            HarnessRunsState.Ready(listOf(workflowRun().copy(driverGeneration = 1))),
            HarnessRunsState.Ready(listOf(workflowRun().copy(cancellation = WorkflowFailure.Cancelled))),
            quiet.copy(isSuspended = true),
            HarnessRunsState.Ready(),
        )
        for (state in stale) spec.assertIgnored(state, changed)
    }

    @Test
    fun `the user answer to a shown request is handed to the owner of the step`() {
        val fenced = HarnessRunsState.Ready(listOf(run.copy(driverGeneration = 3)))
        val effect = HarnessRunsEffect.AnswerOrganism(run.id, 3, asking.step, asking.cell, allow)
        val result = spec.assertTransition(fenced, decide, fenced, listOf(effect))
        assertFalse(result.isStateChange)
        assertTrue(decide is HarnessRunsIntent.Internal && decide is HarnessRunsIntent.Command)
        assertNull(spec.onEffectFailure(effect, Exception()))
    }

    @Test
    fun `no session answers for the user and no answer reaches a request that is not shown`() {
        val foreign = listOf(
            decide.copy(by = WorkflowViewer.Agent(Caller)),
            decide.copy(by = WorkflowViewer.Agent(Caller.copy(nativeId = "other"))),
            decide.copy(run = RunId("wf_missing")),
            decide.copy(step = StepKey("s1")),
            decide.copy(cell = "c2"),
            decide.copy(decision = allow.copy(request = PermissionRequestId("other"))),
            decide.copy(decision = allow.copy(turn = TurnId("other"))),
            decide.copy(decision = allow.copy(option = PermissionOptionId("unoffered"))),
        )
        for (intent in foreign) {
            spec.assertTransition(
                ready,
                intent,
                ready,
                outputs = listOf(HarnessRunsOutput.Rejected(Request, WorkflowRejection.NotFound)),
            )
        }
    }

    @Test
    fun `a finished cancelled or suspended run answers nothing`() {
        val done = run.copy(status = WorkflowStatus.Failed(WorkflowFailure.Error), finishedAt = Now)
        val cancelled = run.copy(cancellation = WorkflowFailure.Cancelled)
        val cases = listOf(
            HarnessRunsState.Ready(listOf(done)) to WorkflowRejection.AlreadyFinished,
            HarnessRunsState.Ready(listOf(cancelled)) to WorkflowRejection.Unavailable,
            ready.copy(isSuspended = true) to WorkflowRejection.Unavailable,
        )
        for ((state, reason) in cases) {
            spec.assertTransition(state, decide, state, outputs = listOf(HarnessRunsOutput.Rejected(Request, reason)))
        }
        val unready = listOf(HarnessRunsState.Idle(), HarnessRunsState.Loading(), HarnessRunsState.Failed())
        for (state in unready) {
            spec.assertTransition(
                state,
                decide,
                state,
                outputs = listOf(HarnessRunsOutput.Rejected(Request, WorkflowRejection.Unavailable)),
            )
        }
    }

    @Test
    fun `the requests of a driver generation end with it`() {
        val quiet = HarnessRunsState.Ready(listOf(run.copy(organismRequests = emptyList())))
        val pause = listOf(HarnessRunsEffect.Pause(listOf(run)))
        spec.assertTransition(ready, HarnessRunsIntent.Internal.Suspended, quiet.fenced(1, isSuspended = true), pause)
        val suspended = ready.copy(isSuspended = true)
        val resumed = quiet.fenced(1)
        val drive = listOf(HarnessRunsEffect.Drive(listOf(resumed.runs.single())))
        spec.assertTransition(suspended, HarnessRunsIntent.Internal.Resumed, resumed, drive)
        val recovered = quiet.runs.single().copy(attempt = 1, driverGeneration = 1)
        spec.assertTransition(
            ready,
            HarnessRunsIntent.Internal.Recovered(run.id, 1, 1),
            HarnessRunsState.Ready(listOf(recovered)),
            listOf(HarnessRunsEffect.Drive(listOf(recovered))),
        )
        val cancelled = quiet.runs.single().copy(driverGeneration = 1, cancellation = WorkflowFailure.Cancelled)
        spec.assertTransition(
            ready,
            HarnessRunsIntent.Internal.CancelRun(Request, run.id, WorkflowViewer.User),
            HarnessRunsState.Ready(listOf(cancelled)),
            listOf(HarnessRunsEffect.StopRun(cancelled)),
            listOf(HarnessRunsOutput.CancellationRequested(Request, run.id)),
        )
        val status = WorkflowStatus.Completed(JsonPrimitive("ok"))
        val finished = quiet.runs.single().copy(status = status, finishedAt = Now)
        spec.assertTransition(
            ready,
            HarnessRunsIntent.Internal.Finished(run.id, 0, status, Now),
            HarnessRunsState.Ready(listOf(finished)),
            outputs = listOf(HarnessRunsOutput.RunFinished(run.id, status)),
        )
    }

    @Test
    fun `a new run cannot arrive with live requests`() {
        val start = HarnessRunsIntent.Internal.StartRun(Request, run, Now)
        spec.assertTransition(
            HarnessRunsState.Ready(),
            start,
            HarnessRunsState.Ready(),
            outputs = listOf(HarnessRunsOutput.Rejected(Request, WorkflowRejection.InvalidRun)),
        )
    }

    @Test
    fun `request texts never reach a rendered description`() {
        val text = "private $SECRET"
        val sensitive = OrganismPermission(asking.step, asking.cell, text, request(text))
        val answered = allow.copy(answer = PermissionAnswer.Text(text))
        val rendered = listOf(
            sensitive.toString(),
            HarnessRunsIntent.Internal.OrganismPermissionsChanged(run.id, 0, listOf(sensitive)).toString(),
            decide.copy(decision = answered).toString(),
            HarnessRunsEffect.AnswerOrganism(run.id, 0, asking.step, asking.cell, answered).toString(),
            run.copy(organismRequests = listOf(sensitive)).toString(),
        )
        assertTrue(rendered.none { text in it || SECRET in it })
    }

    private fun HarnessRunsState.Ready.fenced(generation: Long, isSuspended: Boolean = false) =
        copy(runs = runs.map { it.copy(driverGeneration = generation) }, isSuspended = isSuspended)

    private fun request(text: String = "Run a command?") = PermissionRequest(
        PermissionRequestId("req"),
        TurnId("turn"),
        text,
        listOf(
            PermissionOption(PermissionOptionId("allow"), "Allow"),
            PermissionOption(PermissionOptionId("deny"), "Deny"),
        ),
        description = text,
    )
}
