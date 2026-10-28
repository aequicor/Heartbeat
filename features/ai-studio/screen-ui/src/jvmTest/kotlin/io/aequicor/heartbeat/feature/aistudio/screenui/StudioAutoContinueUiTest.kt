package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_chip_suggest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_mode_autopilot
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_paused
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_send_now
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_stop
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
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
import java.io.File
import javax.imageio.ImageIO
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
    fun `offered replies are inserted without sending`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<StudioIntent>()
        val ui = AutoContinueUi(
            mode = AutoContinueModeUi.Suggest,
            options = persistentListOf(AutoContinueOptionUi("Дальше", "Продолжай со шага 3")),
        )
        render(state(ui), events)
        onNodeWithTag("auto-continue-option-0-0").performClick()
        onNodeWithTag("auto-continue-insert-0-0").assertDoesNotExist()
        onNodeWithTag("auto-continue-refresh-0").performClick()
        runOnIdle {
            assertEquals(
                listOf<StudioIntent>(
                    StudioIntent.PickAutoContinue("chat", 0, 0, isSend = false, text = "Продолжай со шага 3"),
                    StudioIntent.RefreshAutoContinue("chat"),
                ),
                events,
            )
        }
    }

    @Test
    fun `the compact autopilot strip can be stopped`() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        val events = mutableListOf<StudioIntent>()
        val ui = AutoContinueUi(
            mode = AutoContinueModeUi.Autopilot,
            countdown = AutoContinueCountdownUi(1, "Продолжай", 5),
        )
        val labels = render(state(ui), events)
        onNodeWithText(labels.stop).performClick()
        runOnIdle {
            assertEquals(
                listOf<StudioIntent>(StudioIntent.StopAutoContinue("chat")),
                events,
            )
        }
    }

    @Test
    fun `a hidden off mode can be enabled from the add menu`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<StudioIntent>()
        val labels = render(state(AutoContinueUi(mode = AutoContinueModeUi.Off)), events)
        onNodeWithTag("auto-continue-mode-0").assertDoesNotExist()
        onNodeWithContentDescription(labels.add).performClick()
        onNodeWithText(labels.menu).performScrollTo().performClick()
        onNodeWithText(labels.autopilot).performClick()
        runOnIdle {
            assertEquals(
                listOf<StudioIntent>(StudioIntent.SetAutoContinue("chat", AutoContinueModeUi.Autopilot)),
                events,
            )
        }
    }

    @Test
    fun `autopilot shows no manual options while the agent runs`() = runSkikoComposeUiTest(size = Size(834f, 1112f)) {
        val ui = AutoContinueUi(
            mode = AutoContinueModeUi.Autopilot,
            options = persistentListOf(AutoContinueOptionUi("Дальше", "Продолжай")),
        )
        val running = state(ui).copy(sessions = persistentListOf(chat.copy(isRunning = true)))
        val labels = render(running, mutableListOf())
        onNodeWithText(labels.stop).assertExists()
        onNodeWithTag("auto-continue-option-0-0").assertDoesNotExist()
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

    @Test
    fun `a paused autopilot reports waiting instead of running`() = runSkikoComposeUiTest {
        val labels = render(
            state(AutoContinueUi(mode = AutoContinueModeUi.Autopilot, isPaused = true)),
            mutableListOf(),
        )
        onNodeWithText(labels.paused).assertExists()
        onNodeWithText(labels.stop).assertExists()
    }

    @Test
    fun `approved layouts render in both themes and host densities`() {
        for (width in listOf(390f, 834f, 1280f)) {
            for (dark in listOf(false, true)) {
                verifyLayout(width, dark, AutoContinueModeUi.Suggest)
                verifyLayout(width, dark, AutoContinueModeUi.Autopilot)
            }
        }
    }

    private fun verifyLayout(width: Float, dark: Boolean, mode: AutoContinueModeUi) {
        runSkikoComposeUiTest(size = Size(width, 844f)) {
            val labels = render(
                state(
                    AutoContinueUi(
                        mode = mode,
                        options = persistentListOf(
                            AutoContinueOptionUi("Проверить логи", "Покажи логи отправки и получения ответа"),
                            AutoContinueOptionUi("Разобрать код", "Найди причину задержки в коде композера"),
                            AutoContinueOptionUi("Предложить исправление", "Выбери исправление и запусти тесты"),
                        ),
                    ),
                ),
                mutableListOf(),
                isDark = dark,
                isMobile = width < 1200f,
            )
            if (mode == AutoContinueModeUi.Autopilot) {
                onNodeWithText(labels.stop).assertIsDisplayed()
            } else {
                onNodeWithTag("auto-continue-option-0-2").assertIsDisplayed()
            }
            val directory = File("build/previews").apply { mkdirs() }
            check(
                ImageIO.write(
                    captureToImage().toAwtImage(),
                    "png",
                    File(directory, "$mode-$width-$dark.png"),
                ),
            )
            onNodeWithTag("auto-continue-mode-0").performClick()
            onNodeWithText(labels.autopilot).assertIsDisplayed()
        }
    }

    private data class Labels(
        val sendNow: String,
        val stop: String,
        val autopilot: String,
        val chip: String,
        val add: String = "",
        val menu: String = "",
        val paused: String = "",
    )

    private fun SkikoComposeUiTest.render(
        state: AiStudioScreenState,
        events: MutableList<StudioIntent>,
        isDark: Boolean = false,
        isMobile: Boolean = false,
    ): Labels {
        var labels = Labels("", "", "", "")
        setContent {
            labels = Labels(
                stringResource(Res.string.auto_continue_send_now),
                stringResource(Res.string.auto_continue_stop),
                stringResource(Res.string.auto_continue_mode_autopilot),
                stringResource(Res.string.auto_continue_chip_suggest),
                stringResource(Res.string.composer_add),
                stringResource(Res.string.auto_continue_menu),
                stringResource(Res.string.auto_continue_paused),
            )
            HbTheme(
                darkTheme = isDark,
                dimensions = if (isMobile) HbDimensions.Mobile else HbDimensions.Desktop,
                motion = HbMotion(isReducedMotion = true),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Column {
                        val content = state.paneContent(pane)
                        AutoContinueStrip(content, { events += it })
                        StudioComposer(content, { events += it }, isCompact = isMobile)
                    }
                }
            }
        }
        waitForIdle()
        return labels
    }
}
