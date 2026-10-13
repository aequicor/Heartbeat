package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarness
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.InputSupportUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraft
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_paste
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_stop
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StudioCompactComposerUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useRussianLabels() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `session context and actions fit single rows on phone tablet and desktop in both themes`() {
        for (width in listOf(320f, 390f, 650f, 1280f)) {
            for (dark in listOf(false, true)) renderComposer(width, dark)
        }
    }

    @Test
    fun `usage and default effort leave usable model controls on small phones and tablets`() {
        for (width in listOf(320f, 480f, 500f)) renderComposer(width, dark = false, withUsage = true)
    }

    private fun renderComposer(width: Float, dark: Boolean, withUsage: Boolean = false) =
        runSkikoComposeUiTest(size = Size(width, 500f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val pane = PaneUi(0, sessionId = "chat")
            val state = fixture().let {
                if (withUsage) {
                    it.copy(
                        contexts = persistentMapOf("chat" to ContextUsageUi(1000, 10000, 10)),
                        configurations = persistentMapOf(
                            "chat" to SessionConfigurationUi("route", null, ApprovalUi.Ask),
                        ),
                    )
                } else {
                    it
                }
            }.withDraft(0, "Помоги улучшить интерфейс")
            var add = ""
            var attach = ""
            var paste = ""
            var send = ""
            setContent {
                add = stringResource(Res.string.composer_add)
                attach = stringResource(Res.string.attachments_add)
                paste = stringResource(Res.string.attachments_paste)
                send = stringResource(Res.string.composer_send)
                HbTheme(
                    darkTheme = dark,
                    dimensions = if (width >= 1280f) HbDimensions.Desktop else HbDimensions.Mobile,
                ) {
                    Box(
                        Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.m),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        StudioComposer(state.paneContent(pane), events::add, isCompact = width < 720f)
                    }
                }
            }
            assertCompactLayout(width, add, send)
            saveComposer("composer-$width-$dark-$withUsage")
            if (width < 496f) {
                onNodeWithTag("attachment-paste-0").assertDoesNotExist()
            } else {
                onNodeWithTag("attachment-paste-0").assertIsDisplayed()
            }
            onNodeWithContentDescription(send).performClick()
            onNodeWithContentDescription(add).performClick()
            onNodeWithText(attach).assertIsDisplayed()
            if (width < 496f) onNodeWithText(paste).assertIsDisplayed()
            onNodeWithText(attach).performClick()
            runOnIdle {
                assertEquals(
                    listOf<AiStudioScreenIntent>(
                        AiStudioScreenIntent.Submit(0),
                        AiStudioScreenIntent.PickAttachments(0),
                    ),
                    events,
                )
            }
        }

    private fun SkikoComposeUiTest.assertCompactLayout(width: Float, add: String, send: String) {
        val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
        val project = onNodeWithTag("session-project-0").assertHasNoClickAction().fetchSemanticsNode().boundsInRoot
        val harness = onNodeWithTag("harness-picker-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(harness.center.y, project.center.y, 1f, "Fixed project and harness must share a row")
        onNodeWithTag("project-chip-0").assertDoesNotExist()
        val addBounds = onNodeWithContentDescription(add).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val model = onNodeWithTag("model-chip").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val effort = onNodeWithTag("effort-chip").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val action = onNodeWithContentDescription(send).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        for (bounds in listOf(model, effort, action)) {
            assertEquals(addBounds.center.y, bounds.center.y, 1f, "Toolbar must fit on one row")
            assertTrue(
                bounds.left >= composer.left && bounds.right <= composer.right,
                "Controls must remain inside editor",
            )
        }
        assertTrue(model.width >= 48f, "Model must keep a usable label and hit target")
        assertTrue(composer.height <= 180f, "One-line composer should not reserve empty rows: ${composer.height}")
        if (width < 1280f) {
            for (bounds in listOf(harness, addBounds, model, effort, action)) assertTrue(bounds.height >= 44f)
        }
    }

    private fun SkikoComposeUiTest.saveComposer(name: String) {
        val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
        val directory = File("build/reports/compact-composer").apply { mkdirs() }
        val screenshot = captureToImage().toAwtImage()
        val top = (composer.top.toInt() - 8).coerceAtLeast(0)
        val crop = screenshot.getSubimage(0, top, screenshot.width, screenshot.height - top)
        check(ImageIO.write(crop, "png", File(directory, "$name.png")))
    }

    @Test
    fun `resizing retains draft caret focus and open model menu with long names`() =
        runSkikoComposeUiTest(size = Size(1280f, 600f)) {
            var width by mutableStateOf(900.dp)
            var state by mutableStateOf(fixture(longNames = true))
            val pane = PaneUi(0, sessionId = "chat")
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        StudioComposer(
                            state.paneContent(pane),
                            {
                                if (it is AiStudioScreenIntent.Suggestions.DraftChanged) {
                                    state = state.withDraft(it.paneId, it.text)
                                }
                            },
                            isCompact = false,
                            modifier = Modifier.width(width),
                        )
                    }
                }
            }
            val editor = onNode(hasSetTextAction())
            editor.performTextInput("Keep draft")
            runOnIdle { width = 320.dp }
            editor.assertIsFocused()
            assertEquals("Keep draft", editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            val model = onNodeWithTag("model-chip")
            model.performClick()
            onNodeWithText(LONG_MODEL).assertIsDisplayed()
            runOnIdle { width = 900.dp }
            onNodeWithText(LONG_MODEL).assertIsDisplayed().performClick()
            onNodeWithContentDescription(LONG_MODEL).assertIsFocused()
        }

    @Test
    fun `running session keeps stop reachable and omits attachment actions`() =
        runSkikoComposeUiTest(size = Size(390f, 500f)) {
            val state = fixture().copy(running = persistentSetOf("chat"))
            val events = mutableListOf<AiStudioScreenIntent>()
            var stop = ""
            var add = ""
            var attach = ""
            var paste = ""
            setContent {
                stop = stringResource(Res.string.composer_stop)
                add = stringResource(Res.string.composer_add)
                attach = stringResource(Res.string.attachments_add)
                paste = stringResource(Res.string.attachments_paste)
                HbTheme(dimensions = HbDimensions.Mobile) {
                    StudioComposer(state.paneContent(PaneUi(0, sessionId = "chat")), events::add, isCompact = true)
                }
            }
            onNodeWithContentDescription(stop).assertIsDisplayed().performClick()
            onNodeWithContentDescription(add).performClick()
            onNodeWithText(attach).assertDoesNotExist()
            onNodeWithText(paste).assertDoesNotExist()
            runOnIdle { assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.Stop("chat")), events) }
        }

    @Test
    fun `keyboard focus can enter the compact editor and long draft grows only to its cap`() =
        runSkikoComposeUiTest(size = Size(390f, 600f)) {
            var state by mutableStateOf(fixture())
            setContent {
                HbTheme(dimensions = HbDimensions.Mobile) {
                    StudioComposer(state.paneContent(PaneUi(0, sessionId = "chat")), {}, isCompact = true)
                }
            }
            val editor = onNode(hasSetTextAction())
            editor.performSemanticsAction(SemanticsActions.RequestFocus)
            editor.assertIsFocused()
            val initial = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.height
            runOnIdle { state = state.withDraft(0, "One\nTwo\nThree") }
            assertTrue(onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.height > initial)
            runOnIdle { state = state.withDraft(0, (1..50).joinToString("\n")) }
            assertEquals(120f, editor.fetchSemanticsNode().boundsInRoot.height)
        }

    private fun fixture(longNames: Boolean = false): AiStudioScreenState = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0, sessionId = "chat")),
        sessions = persistentListOf(SessionUi("chat", "Session", "project", Instant.fromEpochMilliseconds(0))),
        projects = persistentListOf(ProjectUi("project", "androidnative", EnvironmentUi.Local, "master")),
        models = persistentListOf(
            ModelUi(
                "route",
                if (longNames) LONG_MODEL else "glm-5.3-flash",
                shortName = if (longNames) "Модель с очень длинным названием" else "glm-5.3-flash",
                reasoningEfforts = persistentListOf("max"),
                isLocalProjectSupported = true,
                inputSupport = InputSupportUi(persistentListOf("image/png"), persistentListOf("image/png")),
            ),
        ),
        configurations = persistentMapOf("chat" to SessionConfigurationUi("route", "max", ApprovalUi.Ask)),
        isAttachmentsEnabled = true,
        harnessChoices = HarnessChoicesUi(
            options = persistentListOf(StudioHarness("harness", if (longNames) LONG_HARNESS else "features")),
            chats = persistentMapOf("chat" to persistentSetOf("harness")),
        ),
    )
}

private const val LONG_MODEL = "Очень длинное название модели и подключения пользователя"
private const val LONG_HARNESS = "Очень длинное название харнесса проекта с дополнительными инструкциями"
