package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAutoContinuePort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.isAllowedRemotely
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueCountdownUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueOutput
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.ChatAuto
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationOption
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationStatus
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationVerdict
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuerFailure
import io.aequicor.heartbeat.feature.autocontinue.api.HaltReason
import io.aequicor.heartbeat.feature.autocontinue.api.Phase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioAutoContinueModelTest {
    private val option = ContinuationOption("Дальше", "Продолжай со шага 3")
    private val proceed = ContinuationVerdict(ContinuationStatus.Continue, "plan open", listOf(option))
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "chat"), StudioPane(1)),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
    )

    @Test
    fun `phases are projected for the screen`() {
        val suggest = ChatAuto(AutoContinueMode.Suggest)
        val offered = AutoContinueUi(
            mode = AutoContinueModeUi.Suggest,
            options = persistentListOf(AutoContinueOptionUi("Дальше", "Продолжай со шага 3")),
            reason = "plan open",
        )
        assertEquals(offered, suggest.copy(phase = Phase.Offered(proceed)).toUi())
        assertTrue(suggest.copy(phase = Phase.Thinking(1)).toUi().isThinking)
        assertEquals(
            AutoContinueNoticeUi.Done,
            suggest.copy(phase = Phase.Offered(ContinuationVerdict(ContinuationStatus.Done, "ok"))).toUi().notice,
        )
        val autopilot = ChatAuto(AutoContinueMode.Autopilot, autoTurns = 2)
        assertEquals(
            AutoContinueCountdownUi(4, "go", 5),
            autopilot.copy(phase = Phase.Countdown(4, "go", 5)).toUi().countdown,
        )
        assertTrue(autopilot.copy(phase = Phase.Paused).toUi().isPaused)
        assertEquals(
            AutoContinueNoticeUi.ContinuerUnavailable,
            suggest.copy(phase = Phase.Halted(HaltReason.ContinuerFailed, failure = ContinuerFailure.Unavailable))
                .toUi().notice,
        )
        assertEquals(
            AutoContinueNoticeUi.Looping,
            suggest.copy(phase = Phase.Halted(HaltReason.Looping, proceed)).toUi().notice,
        )
        assertFalse("Продолжай" in offered.toString())
    }

    @Test
    fun `the screen shows the chats and picks send through the composer`() = runTest {
        val port = RecordingPort()
        port.state.value = AutoContinueState.Ready(
            AutoContinueSettings(defaultMode = AutoContinueMode.Suggest, autoTurnLimit = 7),
            mapOf("chat" to ChatAuto(AutoContinueMode.Suggest, Phase.Offered(proceed))),
        )
        val fixture = AiStudioModelTest.Fixture(this, ready, autoContinue = port)
        val screen = fixture.subscribe()
        runCurrent()
        val ui = screen.states.value.autoContinue
        assertTrue(ui.isEnabled)
        assertEquals(7, ui.autoTurnLimit)
        assertEquals(AutoContinueModeUi.Suggest, ui.chat("other")?.mode)
        assertEquals(1, ui.chat("chat")?.options?.size)

        val reply = "Продолжай со шага 3"
        screen.intent(AiStudioScreenIntent.PickAutoContinue("chat", paneId = 0, index = 0, isSend = true, text = reply))
        runCurrent()
        val submit = fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().single()
        assertEquals(reply, submit.prompt)
        assertEquals(reply, submit.userInstruction)
    }

    @Test
    fun `a pick of another offer or chat is dropped and a typed draft is kept`() = runTest {
        val port = RecordingPort()
        port.state.value = AutoContinueState.Ready(
            chats = mapOf("chat" to ChatAuto(AutoContinueMode.Suggest, Phase.Offered(proceed))),
        )
        val fixture = AiStudioModelTest.Fixture(this, ready, autoContinue = port)
        val screen = fixture.subscribe()
        runCurrent()
        screen.intent(AiStudioScreenIntent.PickAutoContinue("chat", 0, 0, isSend = true, text = "an older reply"))
        screen.intent(AiStudioScreenIntent.PickAutoContinue("other", 0, 0, isSend = true, text = "Продолжай со шага 3"))
        runCurrent()
        assertEquals("", screen.states.value.draft(0))

        screen.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "my note"))
        screen.intent(AiStudioScreenIntent.PickAutoContinue("chat", 0, 0, isSend = true, text = "Продолжай со шага 3"))
        runCurrent()
        assertEquals("my note\n\nПродолжай со шага 3", screen.states.value.draft(0))
        assertTrue(fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().isEmpty())
    }

    @Test
    fun `commands and typing reach the machine and edits fill the draft`() = runTest {
        val port = RecordingPort()
        port.state.value = AutoContinueState.Ready(
            chats = mapOf("chat" to ChatAuto(AutoContinueMode.Autopilot, Phase.Countdown(1, "go", 5, ENDS_AT))),
        )
        val fixture = AiStudioModelTest.Fixture(this, ready, autoContinue = port)
        val screen = fixture.subscribe()
        runCurrent()
        val intents = listOf(
            AiStudioScreenIntent.SetAutoContinue("chat", AutoContinueModeUi.Suggest),
            AiStudioScreenIntent.RefreshAutoContinue("chat"),
            AiStudioScreenIntent.SendAutoContinueNow("chat"),
            AiStudioScreenIntent.EditAutoContinue("chat"),
            AiStudioScreenIntent.StopAutoContinue("chat"),
        )
        intents.forEach { screen.intent(it) }
        screen.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "my own"))
        runCurrent()
        assertEquals(
            listOf(
                AutoContinueIntent.Public.SetMode("chat", AutoContinueMode.Suggest),
                AutoContinueIntent.Public.Refresh("chat"),
                AutoContinueIntent.Public.SendNow("chat"),
                AutoContinueIntent.Public.Edit("chat"),
                AutoContinueIntent.Public.Stop("chat"),
                AutoContinueIntent.Public.DraftTyped("chat"),
            ),
            port.sent,
        )
        assertTrue(intents.all { isAllowedRemotely(it) })

        port.drafts.emit(AutoContinueOutput.Draft("chat", "edited"))
        runCurrent()
        assertEquals("my own\n\nedited", screen.states.value.draft(0))
        val countdown = screen.states.value.autoContinue.chat("chat")?.countdown
        assertEquals(ENDS_AT, countdown?.deadline)
    }

    @Test
    fun `typing without anything on offer does not reach the machine`() = runTest {
        val port = RecordingPort()
        port.state.value = AutoContinueState.Ready(
            chats = mapOf(
                "chat" to ChatAuto(AutoContinueMode.Suggest),
                "paused" to ChatAuto(AutoContinueMode.Autopilot, Phase.Paused),
            ),
        )
        val fixture = AiStudioModelTest.Fixture(
            this,
            ready.copy(panes = ready.panes + StudioPane(2, "paused")),
            autoContinue = port,
        )
        val screen = fixture.subscribe()
        runCurrent()
        screen.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "text"))
        screen.intent(AiStudioScreenIntent.Suggestions.DraftChanged(1, "new chat"))
        screen.intent(AiStudioScreenIntent.Suggestions.DraftChanged(2, "while paused"))
        runCurrent()
        assertTrue(port.sent.isEmpty())
    }

    @Test
    fun `prompts of autopilot turns are marked in the transcript`() {
        val at = kotlin.time.Instant.fromEpochSeconds(0)
        val messages = listOf(
            StudioMessage.Prompt("p1", at, "Сделай фичу", turn = "t1"),
            StudioMessage.Prompt("p2", at, "Продолжай", turn = "t2"),
            StudioMessage.Prompt("p3", at, "Без хода"),
        )
        val marked = messages.toTranscriptUi(setOf("t2")).filterIsInstance<MessageUi.Prompt>()
        assertEquals(listOf(false, true, false), marked.map { it.isAutopilot })
    }

    private class RecordingPort : StudioAutoContinuePort {
        override val availability = MutableStateFlow(true)
        override val state = MutableStateFlow<AutoContinueState>(AutoContinueState.Idle)
        override val drafts = MutableSharedFlow<AutoContinueOutput.Draft>(extraBufferCapacity = 4)
        val sent = mutableListOf<AutoContinueIntent.Public>()

        override fun autopilotTurns(chat: String) = MutableStateFlow(setOf("t2"))

        override suspend fun send(intent: AutoContinueIntent.Public) {
            sent += intent
        }
    }

    private companion object {
        val ENDS_AT: kotlin.time.Instant = kotlin.time.Instant.fromEpochSeconds(1_000)
    }
}
