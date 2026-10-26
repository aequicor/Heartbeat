package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The feed shows a sent message from submit until the engine's history echoes it. */
class StudioSentPromptModelTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "s-facade")),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )

    @Test
    fun `a sent message shows in the feed until the engine echoes it`() = runTest {
        val clock = TestClock(this)
        val repository = InMemoryStudioRepository(clock)
        val fixture = AiStudioModelTest.Fixture(this, ready, repository)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "Long task"))
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        val submitted = fixture.machine.sent.last() as AiStudioIntent.Public.Submit
        assertEquals("Long task", screen.states.value.sentPrompts.getValue(submitted.submissionId).text)

        fixture.machine.outputs.emit(AiStudioOutput.SubmitAccepted(0, submitted.submissionId, "s-facade"))
        runCurrent()
        assertEquals("", screen.states.value.draft(0))
        assertEquals(1, screen.states.value.sentPrompts.size)

        repository.append("s-facade", StudioMessage.Prompt("s-facade-4", clock.now(), "Long task"))
        runCurrent()
        assertTrue(screen.states.value.sentPrompts.isEmpty())
        fixture.machine.outputs.emit(AiStudioOutput.RunEnded("s-facade", RunOutcome.Completed))
        runCurrent()
        assertTrue(screen.states.value.sentPrompts.isEmpty())
    }

    @Test
    fun `a rejected or unechoed message leaves the feed`() = runTest {
        val clock = TestClock(this)
        val repository = InMemoryStudioRepository(clock)
        val fixture = AiStudioModelTest.Fixture(this, ready, repository)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "Refused"))
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        val refused = fixture.machine.sent.last() as AiStudioIntent.Public.Submit
        fixture.machine.outputs.emit(AiStudioOutput.SubmitRejected(0, refused.submissionId, "s-facade"))
        runCurrent()
        assertTrue(screen.states.value.sentPrompts.isEmpty())
        assertEquals("Refused", screen.states.value.draft(0))

        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        val failed = fixture.machine.sent.last() as AiStudioIntent.Public.Submit
        fixture.machine.outputs.emit(AiStudioOutput.SubmitAccepted(0, failed.submissionId, "s-facade"))
        fixture.machine.outputs.emit(AiStudioOutput.RunEnded("s-facade", RunOutcome.Failed))
        runCurrent()
        assertTrue(screen.states.value.sentPrompts.getValue(failed.submissionId).isRunEnded)
        repository.append("s-facade", StudioMessage.Failed("s-facade-4", clock.now()))
        runCurrent()
        assertTrue(screen.states.value.sentPrompts.isEmpty())
    }
}
