package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_chip_suggest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_mode_autopilot
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_send_now
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_stop
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueCountdownUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueScreenUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

@OptIn(ExperimentalTestApi::class)
class StudioAutoContinueUiTest {
    private val chat = SessionUi("chat", "Chat", projectId = null, updatedAt = Instant.DISTANT_PAST)
    private val pane = PaneUi(0, sessionId = "chat")

    private fun state(ui: AutoContinueUi) = AiStudioScreenState(
        panes = persistentListOf(pane),
        sessions = persistentListOf(chat),
        models = StudioModelOptions,
        autoContinue = AutoContinueScreenUi(
            isEnabled = true,
            autoTurnLimit = 10,
            chats = persistentMapOf("chat" to ui),
        ),
    )

    @Test
    fun `offered replies send or go to the composer`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<StudioIntent>()
        val ui = AutoContinueUi(
            mode = AutoContinueModeUi.Suggest,
            options = persistentListOf(AutoContinueOptionUi("Дальше", "Продолжай со шага 3")),
        )
        render(state(ui), events)
        onNodeWithTag("auto-continue-option-0-0").performClick()
        onNodeWithTag("auto-continue-insert-0-0").performClick()
        onNodeWithTag("auto-continue-refresh-0").performClick()
        runOnIdle {
            assertEquals(
                listOf<StudioIntent>(
                    StudioIntent.PickAutoContinue("chat", 0, 0, isSend = true),
                    StudioIntent.PickAutoContinue("chat", 0, 0, isSend = false),
                    StudioIntent.RefreshAutoContinue("chat"),
                ),
                events,
            )
        }
    }

    @Test
    fun `the countdown can be sent at once or stopped`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<StudioIntent>()
        val ui = AutoContinueUi(
            mode = AutoContinueModeUi.Autopilot,
            countdown = AutoContinueCountdownUi(1, "Продолжай", 5),
        )
        val labels = render(state(ui), events)
        onNodeWithText(labels.sendNow).performClick()
        onNodeWithText(labels.stop).performClick()
        runOnIdle {
            assertEquals(
                listOf<StudioIntent>(StudioIntent.SendAutoContinueNow("chat"), StudioIntent.StopAutoContinue("chat")),
                events,
            )
        }
    }

    @Test
    fun `the composer offers the auto-continue modes of an ordinary chat`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val labels = render(state(AutoContinueUi(mode = AutoContinueModeUi.Suggest)), mutableListOf())
            onAllNodes(hasText(labels.chip) or hasContentDescription(labels.chip), useUnmergedTree = true).onFirst()
                .performClick()
            onNodeWithText(labels.autopilot).assertExists()
        }

    @Test
    fun `nothing is offered while the feature is off`() {
        val off = state(AutoContinueUi()).copy(autoContinue = AutoContinueScreenUi(isEnabled = false))
        assertNull(off.paneContent(pane).autoContinue)
    }

    private data class Labels(val sendNow: String, val stop: String, val autopilot: String, val chip: String)

    private fun SkikoComposeUiTest.render(state: AiStudioScreenState, events: MutableList<StudioIntent>): Labels {
        var labels = Labels("", "", "", "")
        setContent {
            labels = Labels(
                stringResource(Res.string.auto_continue_send_now),
                stringResource(Res.string.auto_continue_stop),
                stringResource(Res.string.auto_continue_mode_autopilot),
                stringResource(Res.string.auto_continue_chip_suggest),
            )
            HbTheme(darkTheme = false) {
                Column {
                    val content = state.paneContent(pane)
                    AutoContinueStrip(content, { events += it })
                    StudioComposer(content, { events += it }, isCompact = false)
                }
            }
        }
        waitForIdle()
        return labels
    }
}
