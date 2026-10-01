package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EffortUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SettingsUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.jump_latest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_this_computer
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
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

/** Reference-sized renderings exercise real transcript composition, disclosure and responsive chrome. */
@OptIn(ExperimentalTestApi::class)
class AiStudioMockupUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useReferenceLanguage() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `reference desktop keeps one readable answer and compact floating composer`() =
        renderReference(Size(1536f, 1024f), isDark = false, name = "studio-frosted-reference")

    @Test
    fun `compact reference keeps reply actions above the composer without horizontal overflow`() =
        renderReference(Size(390f, 844f), isDark = false, name = "studio-frosted-compact")

    @Test
    fun `dark reference retains the unified answer and floating controls`() =
        renderReference(Size(1280f, 900f), isDark = true, name = "studio-frosted-dark")

    private fun renderReference(size: Size, isDark: Boolean, name: String) = runSkikoComposeUiTest(size = size) {
        val state = referenceWorkspace()
        val exits = StudioExits(onBack = {}, onOpenToggles = {})
        var jumpLabel = ""
        var computerLabel = ""
        var maxComposerHeight = 0f
        var maxEditorHeight = 0f
        setContent {
            jumpLabel = stringResource(Res.string.jump_latest)
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme(darkTheme = isDark, dimensions = HbDimensions.Desktop) {
                    computerLabel = stringResource(Res.string.worktree_this_computer)
                    maxComposerHeight = emptyComposerMaxHeight(size.width < HbTheme.dimensions.compactBreakpoint.value)
                    maxEditorHeight = HbTheme.dimensions.controlHeight.value
                    AiStudioContent(state, {}, exits)
                }
            }
        }
        val transcript = onNode(
            hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("transcript-reference-chat")),
        )
        transcript.performScrollToNode(hasTestTag("message-header:reference-answer"))
        onAllNodesWithTag("message-header:reference-answer", useUnmergedTree = true).assertCountEquals(1)
        transcript.performScrollToNode(hasText("Команда"))
        onNodeWithText("Команда").performClick()
        transcript.performScrollToNode(hasTestTag("message-footer:reference-answer"))
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        // A semantic scroll reveals the node inside the full-height list, including its overlay regions.
        // The actual latest action also consumes the trailing padding beneath the floating composer.
        if (onAllNodesWithText(jumpLabel).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithText(jumpLabel).performClick()
            mainClock.advanceTimeBy(1000)
            waitForIdle()
        }
        onNodeWithText(jumpLabel).assertDoesNotExist()

        val directory = File("build/reports/ai-studio").apply { mkdirs() }
        check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png")))

        onAllNodesWithTag("message-footer:reference-answer", useUnmergedTree = true).assertCountEquals(1)
        onNodeWithTag("message-footer:reference-answer").assertIsDisplayed()
        onNodeWithTag("model-chip").assertIsDisplayed()
        assertReferenceGeometry(size, computerLabel, maxComposerHeight, maxEditorHeight)
    }
}

/** Empty panel budget: one editor, up to two toolbar rows and a separate project context row. */
@Composable
private fun emptyComposerMaxHeight(isCompact: Boolean): Float {
    val dimensions = HbTheme.dimensions
    val spacing = HbTheme.spacing
    val contextHeight = dimensions.composerPillHeight + spacing.xs * 2
    val toolbarRows = if (isCompact) 2 else 1
    val toolbarHeight = dimensions.composerPillHeight * toolbarRows + spacing.xs * (toolbarRows - 1)
    return (dimensions.controlHeight + toolbarHeight + contextHeight + spacing.m * 2 + spacing.xs * 3).value
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.assertReferenceGeometry(
    size: Size,
    computerLabel: String,
    maxComposerHeight: Float,
    maxEditorHeight: Float,
) {
    val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
    val header = onNodeWithTag("pane-header-0").fetchSemanticsNode().boundsInRoot
    val footer = onNodeWithTag("message-footer:reference-answer").fetchSemanticsNode().boundsInRoot
    val isCompact = size.width < 720f
    assertTrue(composer.left >= 0f && composer.right <= size.width, "Composer must fit the viewport")
    assertTrue(
        composer.height <= maxComposerHeight,
        "Empty composer including its project context must remain compact",
    )
    val context = onNode(hasText(computerLabel) and hasAnyAncestor(hasTestTag("composer-0")))
        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val editor = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer-0")))
        .fetchSemanticsNode().boundsInRoot
    assertTrue(editor.height <= maxEditorHeight, "Empty editor must remain a single compact control")
    assertTrue(context.bottom <= editor.top, "Local project context must remain above the editor")
    assertTrue(composer.bottom <= size.height && composer.top > header.bottom)
    assertTrue(footer.bottom <= composer.top, "The last answer action must clear the floating composer")
    val pane = onNodeWithTag("pane-0").fetchSemanticsNode().boundsInRoot
    val history = onNodeWithTag("transcript-reference-chat").fetchSemanticsNode().boundsInRoot
    assertEquals(pane.top, history.top)
    assertEquals(pane.bottom, history.bottom)
    if (isCompact) {
        onNodeWithTag("studio-sidebar").assertDoesNotExist()
        onNodeWithTag("pane-open-sidebar").assertIsDisplayed()
    } else {
        onNodeWithTag("studio-sidebar").assertIsDisplayed()
        onAllNodesWithText("Чтение файлов").assertCountEquals(1)
        onAllNodesWithTag("session-reference-chat").assertCountEquals(1)
    }
}

private fun referenceReply(time: Instant): MessageUi.Reply {
    val tools = persistentListOf(
        ToolUi(
            "files",
            "Чтение файлов",
            ToolStatusUi.Done,
            "StudioPaneView.kt\nStudioSidebar.kt\nStudioComposer.kt",
            null,
        ),
        ToolUi("command", "Команда", ToolStatusUi.Done, "BUILD SUCCESSFUL\n12 tests completed, 0 failed (8.4s)", null),
        ToolUi("inspection", "Проверка интерфейса", ToolStatusUi.Done, "Готово", null),
    )
    val intro = "Проверю структуру экрана, размеры элементов и читаемость текста."
    val progress = "Сначала посмотрю компоненты студии и проверю сборку."
    val conclusion = """
        ### Что предлагаю

        - Сделать поле ввода компактнее.
        - Сохранить стекло на панелях, а текст — на светлой подложке.
        - Объединить размышления, инструменты и итог в одном сообщении.

        Так весь ход работы остаётся связанным, а ответ — легко читать.
    """.trimIndent()
    return MessageUi.Reply(
        id = "reference-answer",
        createdAt = time,
        text = "$intro\n\n$progress\n\n$conclusion",
        tools = tools,
        isStreaming = false,
        parts = persistentListOf(
            ReplyPartUi.Text("intro", intro),
            ReplyPartUi.Reasoning("reasoning", "Планирую шаги: проанализировать интерфейс, проверить компоненты."),
            ReplyPartUi.Text("progress", progress),
            ReplyPartUi.Tool(tools[0]),
            ReplyPartUi.Tool(tools[1]),
            ReplyPartUi.Tool(tools[2]),
            ReplyPartUi.Text("final", conclusion),
        ),
    )
}

private fun referenceWorkspace(): AiStudioScreenState {
    val time = Instant.parse("2026-09-28T06:24:00Z")
    return AiStudioScreenState(
        phase = StudioPhase.Ready,
        now = time,
        models = persistentListOf(ModelUi("pulse-pro", "Модель", isLocalProjectSupported = true)),
        settings = SettingsUi("pulse-pro", EffortUi.High, ApprovalUi.Ask),
        projects = persistentListOf(
            ProjectUi("design", "Дизайн студии", EnvironmentUi.Local, "master"),
            ProjectUi("components", "Компоненты", EnvironmentUi.Local, "master"),
        ),
        sessions = persistentListOf(
            SessionUi("reference-chat", "Проверка интерфейса", "design", time, isUnread = true),
            SessionUi("overview", "Обзор проекта", "design", time),
            SessionUi("release", "План релиза", "design", time),
            SessionUi("recent-overview", "Обзор проекта", null, time),
            SessionUi("recent-inspection", "Проверка интерфейса", null, time),
        ),
        panes = persistentListOf(PaneUi(0, sessionId = "reference-chat", projectId = "design")),
        transcripts = persistentMapOf(
            "reference-chat" to persistentListOf(
                MessageUi.Prompt("reference-prompt", time, "Проверь интерфейс и предложи улучшения"),
                referenceReply(time),
            ),
        ),
    )
}
