package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarness
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessChoices
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenui.paneContent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioHarnessModelTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "s-facade")),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )

    @Test
    fun `a new chat keeps its harness choice on the pane and hands it to its submission`() = runTest {
        val harnesses = FakeStudioHarnesses()
        val fixture = Fixture(this, ready.copy(panes = listOf(StudioPane(0))), harnesses = harnesses)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(0, "compose", true))
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(0, "verify", true))
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(0, "verify", false))
        runCurrent()
        assertEquals<Set<String>?>(setOf("compose"), screen.states.value.harnessChoices.panes[0])
        assertTrue(harnesses.connections.isEmpty(), "a chat without a session connects nothing yet")

        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "Build the screen"))
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        val submitted = fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().single()
        assertEquals(listOf(submitted.submissionId to setOf("compose")), harnesses.claims)
        assertTrue(0 !in screen.states.value.harnessChoices.panes)

        assertTrue(harnesses.released.isEmpty(), "the turn of a sent submission owns its claim")
    }

    @Test
    fun `a rejected submission releases its harness claim`() = runTest {
        val harnesses = FakeStudioHarnesses()
        val fixture = Fixture(this, ready.copy(panes = listOf(StudioPane(0))), harnesses = harnesses)
        fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(0, "compose", true))
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "Build the screen"))
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        val submitted = fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().single()

        // A session created for a request the pane no longer owns runs no turn that could release the claim.
        fixture.machine.outputs.emit(AiStudioOutput.SubmitRejected(0, submitted.submissionId, "chat"))
        runCurrent()
        assertEquals(listOf(submitted.submissionId), harnesses.released)
    }

    @Test
    fun `a submission the machine refuses releases its harness claim and returns the choice to the pane`() = runTest {
        val harnesses = FakeStudioHarnesses()
        val fixture = Fixture(this, ready.copy(panes = listOf(StudioPane(0))), harnesses = harnesses)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(0, "compose", true))
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "Build the screen"))
        fixture.machine.result = SendResult.Ignored
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()

        val claimed = harnesses.claims.single().first
        assertEquals(listOf(claimed), harnesses.released)
        assertEquals<Set<String>?>(setOf("compose"), screen.states.value.harnessChoices.panes[0])
    }

    @Test
    fun `a new chat that fails to start releases its claim and returns the choice to the pane`() = runTest {
        val harnesses = FakeStudioHarnesses()
        val fixture = Fixture(this, ready.copy(panes = listOf(StudioPane(0))), harnesses = harnesses)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(0, "compose", true))
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "Build the screen"))
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        val submitted = fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().single()
        assertTrue(0 !in screen.states.value.harnessChoices.panes)

        fixture.machine.outputs.emit(
            AiStudioOutput.SubmitFailed(0, "Build the screen", 7, submissionId = submitted.submissionId),
        )
        runCurrent()
        assertEquals(listOf(submitted.submissionId), harnesses.released)
        assertEquals<Set<String>?>(setOf("compose"), screen.states.value.harnessChoices.panes[0])
        assertEquals("Build the screen", screen.states.value.draft(0))
    }

    @Test
    fun `an existing chat connects in the background and shows a change that was not confirmed`() = runTest {
        val native = SessionRef(EngineId("engine"), SessionSourceId("source"), "chat")
        val workspace = StudioWorkspace(
            emptyList(),
            listOf(StudioSession("s-facade", null, "Chat", TestClock(this).now(), nativeSession = native)),
        )
        val repository = object : StudioRepository by InMemoryStudioRepository(TestClock(this)) {
            override fun observeWorkspace() = flowOf(workspace)
        }
        val harnesses = FakeStudioHarnesses()
        harnesses.isConfirmed = false
        val fixture = Fixture(this, ready, repository, harnesses)
        val screen = fixture.subscribe()

        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(0, "compose", true))
        runCurrent()
        assertEquals(listOf(Triple(native, "compose", true)), harnesses.connections)
        assertTrue(harnesses.claims.isEmpty())
        val failed = screen.states.value
        assertEquals<Set<String>>(setOf("s-facade"), failed.harnessChoices.failedChats)
        assertTrue(failed.paneContent(failed.panes.single()).isHarnessFailed)

        harnesses.isConfirmed = true
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(0, "compose", true))
        runCurrent()
        assertTrue(screen.states.value.harnessChoices.failedChats.isEmpty())
    }
}

/** Records claims and connections; the library offers one harness. */
private class FakeStudioHarnesses : StudioHarnesses {
    val claims = mutableListOf<Pair<String, Set<String>>>()
    val released = mutableListOf<String>()
    val connections = mutableListOf<Triple<SessionRef, String, Boolean>>()
    var isConfirmed = true

    override val choices = flowOf(
        StudioHarnessChoices(listOf(StudioHarness("compose", "Compose"))),
    )

    override suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean): Boolean {
        connections += Triple(session, harness, isSelected)
        return isConfirmed
    }

    override fun claim(submissionId: String, harnesses: Set<String>) {
        if (harnesses.isNotEmpty()) claims += submissionId to harnesses
    }

    override fun release(submissionId: String) {
        released += submissionId
    }
}
