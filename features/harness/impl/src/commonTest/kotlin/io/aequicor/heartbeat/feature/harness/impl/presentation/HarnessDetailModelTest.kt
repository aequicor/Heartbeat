package io.aequicor.heartbeat.feature.harness.impl.presentation

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessAuthor
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismPermission
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowViewer
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class HarnessDetailModelTest {
    private val session = SessionRef(EngineId("claude"), SessionSourceId("local"), "chat-1")

    private fun ready() = HarnessState.Ready(
        harnesses = listOf(HarnessEntry(harness.copy(revision = 3))),
        attachments = mapOf(session to setOf(harness.id)),
    )

    @Test
    fun `deleting the harness closes the screen`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)
        val closed = backgroundScope.async { screen.actions.first() }
        runCurrent()

        library.outputs.emit(HarnessOutput.Deleted(request(), harness.id))
        runCurrent()
        assertEquals(HarnessDetailAction.Close, closed.await())
    }

    @Test
    fun `metadata saves trimmed valid values and cancelling drops the edit`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)

        screen.intent(HarnessDetailIntent.EditMeta)
        screen.intent(HarnessDetailIntent.ChangeMeta(" ", "Anything"))
        screen.intent(HarnessDetailIntent.SaveMeta)
        runCurrent()
        assertTrue(library.sent.isEmpty(), "a blank title is not saved")
        assertEquals(MetaDraftUi(" ", "Anything"), screen.states.value.meta)

        screen.intent(HarnessDetailIntent.ChangeMeta("  Compose UI ", " Native screens "))
        screen.intent(HarnessDetailIntent.SaveMeta)
        runCurrent()
        val update = assertIs<HarnessIntent.Internal.Update>(library.sent.single())
        assertEquals(HarnessChange.Meta("Compose UI", "Native screens"), update.change)
        assertNull(screen.states.value.meta)

        screen.intent(HarnessDetailIntent.EditMeta)
        screen.intent(HarnessDetailIntent.ChangeMeta("Other", ""))
        screen.intent(HarnessDetailIntent.CancelMeta)
        runCurrent()
        assertNull(screen.states.value.meta)
        assertEquals(1, library.sent.size)
    }

    @Test
    fun `detaching a connected chat sends its session`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)
        val connected = screen.states.value.attachments.single()

        screen.intent(HarnessDetailIntent.Detach(connected.key))
        runCurrent()
        val detach = assertIs<HarnessIntent.Public.Detach>(library.sent.single())
        assertEquals(harness.id to session, detach.id to detach.session)
    }

    @Test
    fun `new session default follows durable saves and preserves the value on failure`() = runTest {
        val initial = ready()
        val library = RecordingLibrary(initial)
        val screen = subscribe(detail(library).store)
        assertFalse(screen.states.value.isDefaultForNewSessions)

        screen.intent(HarnessDetailIntent.SetDefaultForNewSessions(true))
        runCurrent()
        val enable = assertIs<HarnessIntent.Internal.Update>(library.sent.single())
        assertEquals(HarnessChange.DefaultForNewSessions(true), enable.change)
        assertEquals(HarnessAuthor.User, enable.author)
        assertEquals(harness.id, enable.id)
        assertEquals(3L, enable.expectedRevision)
        assertFalse(screen.states.value.isDefaultForNewSessions, "the pending save is not yet committed")

        library.state.value = initial.copy(
            harnesses = initial.harnesses.map {
                it.copy(harness = it.harness.copy(isDefaultForNewSessions = true, revision = 4))
            },
        )
        runCurrent()
        assertTrue(screen.states.value.isDefaultForNewSessions)

        screen.intent(HarnessDetailIntent.SetDefaultForNewSessions(false))
        runCurrent()
        val disable = assertIs<HarnessIntent.Internal.Update>(library.sent.last())
        assertEquals(HarnessChange.DefaultForNewSessions(false), disable.change)
        assertEquals(4L, disable.expectedRevision)
        library.outputs.emit(HarnessOutput.StorageFailed(disable.requestId))
        runCurrent()
        assertTrue(screen.states.value.isDefaultForNewSessions, "a failed save retains the committed default")
        assertEquals(DetailErrorUi.SaveFailed, screen.states.value.error)
    }

    @Test
    fun `only own requests show errors and the rejection reason picks the message`() = runTest {
        val library = RecordingLibrary(ready())
        val screen = subscribe(detail(library).store)
        screen.intent(HarnessDetailIntent.SetEnabled(false))
        runCurrent()
        val own = assertIs<HarnessIntent.Internal.SetEnabled>(library.sent.single()).requestId

        library.outputs.emit(HarnessOutput.Rejected(request(), HarnessRejection.Conflict))
        library.outputs.emit(HarnessOutput.StorageFailed(request()))
        runCurrent()
        assertNull(screen.states.value.error, "agent writes report to the agent")

        val expected = mapOf(
            HarnessRejection.Busy to DetailErrorUi.Busy,
            HarnessRejection.Conflict to DetailErrorUi.Conflict,
            HarnessRejection.Limit to DetailErrorUi.Limit,
            HarnessRejection.Invalid to DetailErrorUi.SaveFailed,
        )
        expected.forEach { (reason, error) ->
            library.outputs.emit(HarnessOutput.Rejected(own, reason))
            runCurrent()
            assertEquals(error, screen.states.value.error, reason.name)
            screen.intent(HarnessDetailIntent.DismissError)
            runCurrent()
        }
        library.outputs.emit(HarnessOutput.StorageFailed(own))
        runCurrent()
        assertEquals(DetailErrorUi.SaveFailed, screen.states.value.error)
    }

    @Test
    fun `the runs of the harness show the requests of their organism cells`() = runTest {
        val runs = RecordingRuns(HarnessRunsState.Ready(listOf(run(listOf(asking())), run(emptyList(), "wf_two"))))
        val screen = subscribe(detail(RecordingLibrary(ready()), runs).store)

        val shown = screen.states.value.runs.first { it.id == "wf_one" }
        assertEquals(1, shown.awaitingPermissions)
        assertEquals(listOf("s0/c1/turn/req"), shown.requests.map { it.key })
        assertEquals(emptyList(), screen.states.value.runs.first { it.id == "wf_two" }.requests)
        assertFalse("private" in screen.states.value.toString())
    }

    @Test
    fun `an answer is sent to the runs machine as the user for the exact cell`() = runTest {
        val runs = RecordingRuns(HarnessRunsState.Ready(listOf(run(listOf(asking())))))
        val screen = subscribe(detail(RecordingLibrary(ready()), runs).store)
        val request = screen.states.value.runs.single().requests.single()

        screen.intent(HarnessDetailIntent.DecideRequest(request, "deny"))
        runCurrent()
        val decide = assertIs<HarnessRunsIntent.Internal.DecideOrganismPermission>(runs.sent.single())
        assertEquals(RunId("wf_one"), decide.run)
        assertEquals(StepKey("s0"), decide.step)
        assertEquals("c1", decide.cell)
        assertEquals(WorkflowViewer.User, decide.by)
        assertEquals(
            PermissionDecision(TurnId("turn"), PermissionRequestId("req"), PermissionOptionId("deny")),
            decide.decision,
        )
    }

    @Test
    fun `an option the request does not offer sends nothing`() = runTest {
        val runs = RecordingRuns(HarnessRunsState.Ready(listOf(run(listOf(asking())))))
        val screen = subscribe(detail(RecordingLibrary(ready()), runs).store)
        val request = screen.states.value.runs.single().requests.single()

        screen.intent(HarnessDetailIntent.DecideRequest(request, "unoffered"))
        runCurrent()
        assertTrue(runs.sent.isEmpty())
    }

    private fun TestScope.detail(library: RecordingLibrary, runs: HarnessRunsMachine = IdleRuns()) =
        HarnessDetailModel(harness.id.value, library, runs, storeFactory(), backgroundScope)

    private fun run(requests: List<OrganismPermission>, id: String = "wf_one") = WorkflowRun(
        RunId(id), harness.id, ItemId("workflow"),
        PinnedWorkflow("source", "a".repeat(64), 1, emptyMap()), JsonObject(emptyMap()), null,
        WorkflowOrigin.Script, STARTED, STARTED + 1.hours, organismRequests = requests,
    )

    private fun asking() = OrganismPermission(
        StepKey("s0"),
        "c1",
        "Builder",
        PermissionRequest(
            PermissionRequestId("req"),
            TurnId("turn"),
            "private command?",
            listOf(
                PermissionOption(PermissionOptionId("allow"), "Allow"),
                PermissionOption(PermissionOptionId("deny"), "Deny"),
            ),
        ),
    )

    private companion object {
        val STARTED = Instant.parse("2026-10-06T00:00:00Z")
    }
}

private class RecordingRuns(initial: HarnessRunsState) : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()
    val sent = mutableListOf<HarnessRunsIntent>()

    override suspend fun send(intent: HarnessRunsIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}

private class IdleRuns : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow<HarnessRunsState>(HarnessRunsState.Idle())
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()

    override suspend fun send(intent: HarnessRunsIntent): SendResult = SendResult.Accepted
}
