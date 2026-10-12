package io.aequicor.heartbeat.feature.harness.impl.presentation

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun TestScope.detail(library: RecordingLibrary) =
        HarnessDetailModel(harness.id.value, library, IdleRuns(), storeFactory(), backgroundScope)
}

private class IdleRuns : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow<HarnessRunsState>(HarnessRunsState.Idle())
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()

    override suspend fun send(intent: HarnessRunsIntent): SendResult = SendResult.Accepted
}
