package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.LocalHbSurfaceDismissRequest
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_context_panel
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_context_show
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioAppUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioContextPanelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioContextSlotKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

@OptIn(ExperimentalTestApi::class)
class StudioContextPanelUiTest {
    @Test
    fun `a wide summary uses spare space without moving a centered conversation`() = checkConversationBounds(2000f)

    @Test
    fun `a summary shifts a full width conversation and composer left with a leading gutter`() =
        checkConversationBounds(1200f)

    @Test
    fun `a summary narrows the conversation only while context controls still fit one row`() =
        checkConversationBounds(1000f)

    @Test
    fun `a summary below the beside threshold overlays without narrowing the conversation`() {
        for (width in listOf(820f, 420f)) checkConversationBounds(width)
    }

    private fun checkConversationBounds(width: Float) = runSkikoComposeUiTest(size = Size(width, 820f)) {
        var available by mutableStateOf(false)
        val slot = ContextSlot()
        setContent {
            Pane(
                richState().copy(contextPanels = if (available) state().contextPanels else persistentMapOf()),
                slot,
                isCompact = width == 420f,
            )
        }
        val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
        val transcript = onNodeWithTag("transcript-chat").fetchSemanticsNode().boundsInRoot
        assertEquals(0, slot.mounted)
        onNodeWithTag("pane-open-context-0").assertDoesNotExist()
        available = true
        waitForIdle()
        onNodeWithTag("pane-open-context-0").assertIsDisplayed()
        if (width < 1000f) {
            onNodeWithTag("context-action").assertDoesNotExist()
            onNodeWithTag("pane-open-context-0").performClick()
        }
        val openedComposer = assertConversationPlacement(width, composer, transcript)
        assertEquals(1, slot.mounted)
        val panel = onNodeWithTag("pane-context-panel-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = onNodeWithTag("pane-header-0").fetchSemanticsNode().boundsInRoot
        assertEquals(120f, onNodeWithTag("context-body").fetchSemanticsNode().boundsInRoot.height)
        assertTrue(panel.top >= header.bottom, "The floating card must leave the header reachable")
        assertTrue(panel.bottom <= composer.top, "The floating card must leave the composer reachable")
        assertTrue(panel.left >= 0f && panel.right <= width, "The floating card must fit its pane")
        assertTrue(panel.center.x > width / 2, "The floating card stays aligned to the trailing edge")
        if (width < 1000f) {
            assertEquals(header.bottom, panel.top, 1f, "The overlay panel must continue directly below the header")
            assertEquals(width, panel.right, 1f, "The overlay panel must be attached to the pane's end edge")
        }
        if (width >= 1000f) {
            val message = onNodeWithTag("message-footer:r").fetchSemanticsNode().boundsInRoot
            assertTrue(panel.left > message.right, "The card must remain beside the message with a gap")
            assertTrue(panel.left > openedComposer.right, "The card must remain beside the composer with a gap")
        }
        available = false
        waitForIdle()
        onNodeWithTag("pane-open-context-0").assertDoesNotExist()
        assertEquals(composer, onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot)
        assertEquals(transcript, onNodeWithTag("transcript-chat").fetchSemanticsNode().boundsInRoot)
        assertEquals(0, slot.mounted)
    }

    private fun SkikoComposeUiTest.assertConversationPlacement(width: Float, composer: Rect, transcript: Rect): Rect {
        val openedComposer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
        val openedTranscript = onNodeWithTag("transcript-chat").fetchSemanticsNode().boundsInRoot
        when (width) {
            1200f -> {
                assertEquals(composer.width, openedComposer.width, "Spare leading space must be used before narrowing")
                assertEquals(transcript.width, openedTranscript.width)
                assertTrue(openedComposer.left < composer.left && openedTranscript.left < transcript.left)
            }

            1000f -> {
                assertTrue(openedComposer.width < composer.width)
                assertTrue(openedTranscript.width < transcript.width)
                assertContextControlsShareOneRow(openedComposer)
            }

            else -> {
                assertEquals(composer, openedComposer)
                assertEquals(transcript, openedTranscript)
            }
        }
        assertTrue(openedComposer.left > 0f, "The composer must keep a gutter from the sidebar edge")
        assertEquals(openedComposer.center.x, openedTranscript.center.x, 1f, "The chat and input move together")
        return openedComposer
    }

    private fun SkikoComposeUiTest.assertContextControlsShareOneRow(composer: Rect) {
        val harness = onNodeWithTag("harness-picker-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("session-project-0", "usage-0", "app-chip-0")) {
            val control = onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(control.top < harness.bottom && control.bottom > harness.top, "$tag must share one row")
            assertTrue(control.left >= composer.left && control.right <= composer.right)
        }
    }

    @Test
    fun `a floating summary toggles and Escape restores its header trigger at wide and compact widths`() {
        for (width in listOf(1200f, 420f)) checkSummaryFocus(width)
    }

    private fun checkSummaryFocus(width: Float) = runSkikoComposeUiTest(size = Size(width, 820f)) {
        val slot = ContextSlot()
        setContent { Pane(state(), slot, isCompact = width == 420f) }
        if (width >= 1000f) onNodeWithTag("pane-open-context-0").performClick()
        waitForIdle()
        assertEquals(0, slot.mounted)
        onNodeWithTag("pane-open-context-0").assertIsDisplayed().performClick()
        onNodeWithTag("context-action").assertIsDisplayed()
        assertEquals(1, slot.mounted)
        onNodeWithTag("pane-open-context-0").assertIsDisplayed().performClick()
        onNodeWithTag("context-action").assertDoesNotExist()
        assertEquals(0, slot.mounted)
        onNodeWithTag("pane-open-context-0").performClick()
        onNodeWithTag("context-action").assertIsDisplayed()
        assertEquals(1, slot.mounted)
        onNodeWithTag("context-action").requestFocus().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(0, slot.mounted)
        onNodeWithTag("pane-open-context-0").assertIsFocused()
    }

    @Test
    fun `native content can dismiss the floating summary and restore its toggle focus`() = runSkikoComposeUiTest(
        size = Size(1200f, 820f),
    ) {
        val slot = ContextSlot()
        setContent { Pane(state(), slot) }
        onNodeWithTag("context-action").assertIsDisplayed().requestFocus()
        runOnIdle { assertNotNull(slot.dismissRequest).invoke() }
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertDoesNotExist()
        onNodeWithTag("pane-open-context-0").assertIsFocused()
        assertEquals(0, slot.mounted)
    }

    @Test
    fun `an open summary leaves composer input and keyboard navigation available`() = runSkikoComposeUiTest(
        size = Size(1200f, 820f),
    ) {
        var selected by mutableStateOf(state())
        val slot = ContextSlot()
        setContent {
            Pane(selected, slot, onIntent = { intent ->
                if (intent is StudioIntent.Suggestions.DraftChanged) {
                    selected = selected.withDraft(intent.paneId, intent.text)
                }
            })
        }
        onNodeWithTag("context-action").assertIsDisplayed()
        val editor = onNode(hasSetTextAction())
        editor.performMouseInput { click() }
        editor.assertIsFocused().performTextInput("Keep writing with the summary visible")
        runOnIdle {
            assertEquals("Keep writing with the summary visible", selected.paneContent(selected.panes.single()).draft)
        }
        onNodeWithTag("context-action").assertIsDisplayed().requestFocus().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        val panel = hasTestTag("pane-context-panel-0")
        onAllNodes(isFocused() and (panel or hasAnyAncestor(panel))).assertCountEquals(0)
        editor.requestFocus().assertIsFocused()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        assertEquals(1, slot.mounts)
    }

    @Test
    fun `a tall floating summary stays above the composer in a small pane`() = runSkikoComposeUiTest(
        size = Size(360f, 420f),
    ) {
        val slot = ContextSlot(height = 900.dp)
        setContent { Pane(state(), slot, isCompact = true) }
        onNodeWithTag("pane-open-context-0").performClick()
        val panel = onNodeWithTag("pane-context-panel-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = onNodeWithTag("pane-header-0").fetchSemanticsNode().boundsInRoot
        val footer = onNodeWithTag("pane-footer-0").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.top >= header.bottom)
        assertTrue(panel.bottom <= footer.top)
        assertTrue(panel.left >= 0f && panel.right <= 360f)
        assertTrue(panel.height in 1f..419f)
    }

    @Test
    fun `a tall summary leaves the centered new chat input reachable`() = runSkikoComposeUiTest(
        size = Size(420f, 820f),
    ) {
        val initial = state().copy(
            panes = persistentListOf(PaneUi(0)),
            sessions = persistentListOf(),
            transcripts = persistentMapOf(),
            contextPanels = persistentMapOf(0 to StudioContextPanelUi(null, null)),
        )
        val slot = ContextSlot(height = 900.dp)
        setContent { Pane(initial, slot, isCompact = true) }
        onNodeWithTag("pane-open-context-0").performClick()
        val panel = onNodeWithTag("pane-context-panel-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val composer = onNodeWithTag("composer-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(panel.bottom <= composer.top, "The card must stop above a centered input as well as a bottom input")
        val editor = onNode(hasSetTextAction())
        editor.performMouseInput { click() }
        editor.assertIsFocused()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
    }

    @Test
    fun `a narrow split pane keeps the summary compact and its header actions reachable`() = runSkikoComposeUiTest(
        size = Size(360f, 820f),
    ) {
        val state = state()
        val slot = ContextSlot()
        var summaryLabel = ""
        var showLabel = ""
        setContent {
            val label = stringResource(Res.string.pane_context_panel)
            val show = stringResource(Res.string.pane_context_show)
            SideEffect {
                summaryLabel = label
                showLabel = show
            }
            HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
                StudioPaneView(
                    state.paneContent(state.panes.single()),
                    {},
                    PaneLayout(isSplitAllowed = false, isCloseAllowed = true, isCompact = false),
                    Modifier.fillMaxSize(),
                    slots = persistentMapOf(studioContextSlotKey(0) to slot),
                )
            }
        }
        onNodeWithTag("pane-open-context-0").assertIsDisplayed().assert(hasContentDescription(showLabel))
        onNodeWithText(summaryLabel).assertDoesNotExist()
        onNodeWithText("Chat").assertIsDisplayed()
        onNodeWithTag("pane-menu-0").assertIsDisplayed()
        onNodeWithTag("pane-close-0").assertIsDisplayed()
        onNodeWithTag("context-action").assertDoesNotExist()
        onNodeWithTag("pane-open-context-0").performClick()
        onNodeWithTag("context-action").assertIsDisplayed()
        val panel = onNodeWithTag("pane-context-panel-0").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.left >= 0f && panel.right <= 360f)
    }

    @Test
    fun `resizing a floating summary keeps one mounted card`() = runSkikoComposeUiTest(
        size = Size(2000f, 820f),
    ) {
        val slot = ContextSlot()
        var width by mutableStateOf(420.dp)
        setContent { Pane(state(), slot, Modifier.width(width), isCompact = width == 420.dp) }
        onNodeWithTag("pane-open-context-0").performClick()
        onNodeWithTag("context-action").assertIsDisplayed()
        val editor = onNode(hasSetTextAction()).requestFocus()
        width = 2000.dp
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        editor.assertIsFocused()
        width = 1000.dp
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        editor.assertIsFocused()
        width = 420.dp
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        editor.assertIsFocused()
        assertEquals(1, slot.mounted)
        assertEquals(1, slot.maxMounted, "The responsive transition must never mount two copies")
        assertEquals(1, slot.mounts, "Resizing must preserve the open summary's content")
        onNodeWithTag("pane-open-context-0").performClick()
        width = 2000.dp
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertDoesNotExist()
        assertEquals(0, slot.mounted)
    }

    @Test
    fun `automatic visibility shifts then narrows before falling back to a closed overlay`() = runSkikoComposeUiTest(
        size = Size(2000f, 820f),
    ) {
        val slot = ContextSlot()
        var width by mutableStateOf(2000.dp)
        setContent { Pane(state(), slot, Modifier.width(width)) }
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        val inputWidth = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.width
        width = 1200.dp
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        assertEquals(inputWidth, onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.width)
        width = 1000.dp
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        assertTrue(onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.width < inputWidth)
        width = 820.dp
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertDoesNotExist()
        assertEquals(inputWidth, onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.width)
        width = 2000.dp
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        assertEquals(inputWidth, onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.width)
        assertEquals(1, slot.maxMounted)
    }

    @Test
    fun `a returning context becomes visible after the previous context was hidden`() = runSkikoComposeUiTest(
        size = Size(2000f, 820f),
    ) {
        var available by mutableStateOf(true)
        val slot = ContextSlot()
        setContent {
            Pane(state().copy(contextPanels = if (available) state().contextPanels else persistentMapOf()), slot)
        }
        onNodeWithTag("context-action").assertIsDisplayed()
        onNodeWithTag("pane-open-context-0").performClick()
        onNodeWithTag("context-action").assertDoesNotExist()
        available = false
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertDoesNotExist()
        onNodeWithTag("pane-open-context-0").assertDoesNotExist()
        assertEquals(0, slot.mounted)
        available = true
        waitForIdle()
        onNodeWithTag("pane-open-context-0").assertIsDisplayed()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        assertEquals(1, slot.mounted)
    }

    @Test
    fun `switching chats shows the new summary even when the previous one was hidden`() = runSkikoComposeUiTest(
        size = Size(2000f, 820f),
    ) {
        var selected by mutableStateOf(state())
        val slot = ContextSlot()
        setContent { Pane(selected, slot) }
        onNodeWithTag("context-action").assertIsDisplayed()
        onNodeWithTag("pane-open-context-0").performClick()
        onNodeWithTag("context-action").assertDoesNotExist()
        selected = state("other-chat")
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        onNodeWithTag("pane-open-context-0").assertIsDisplayed()
        assertEquals(1, slot.mounted)
        onNodeWithTag("pane-open-context-0").performClick()
        selected = state()
        waitForIdle()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        onNodeWithTag("pane-open-context-0").assertIsDisplayed()
        assertEquals(1, slot.mounted)
    }

    @Test
    fun `closed and open summaries render with the full studio at wide and compact widths in both themes`() {
        for (isDark in listOf(false, true)) {
            for (width in listOf(2000f, 1280f, 420f)) reference(width, isDark)
        }
    }

    private fun reference(width: Float, isDark: Boolean) = runSkikoComposeUiTest(size = Size(width, 900f)) {
        setContent {
            HbTheme(darkTheme = isDark, dimensions = HbDimensions.Desktop) {
                AiStudioContent(
                    referenceState(),
                    {},
                    StudioExits(slots = persistentMapOf(studioContextSlotKey(0) to ReferenceSlot)),
                )
            }
        }
        val directory = File("build/reports/ai-studio/context-panel").apply { mkdirs() }
        val prefix = "studio-${width.toInt()}-${if (isDark) "dark" else "light"}"
        onNodeWithTag("pane-open-context-0").assertIsDisplayed()
        if (width < 1000f) {
            onNodeWithTag("pane-context-panel-0").assertDoesNotExist()
            onNodeWithTag("pane-open-context-0").performClick()
        }
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$prefix-open.png")))
        onNodeWithTag("pane-open-context-0").performClick()
        onNodeWithTag("pane-context-panel-0").assertDoesNotExist()
        check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$prefix-closed.png")))
    }

    private fun referenceState() = richState().copy(
        sessions = persistentListOf(SessionUi("chat", "Тренировки и прогресс · ORBITA", "sport", Instant.DISTANT_PAST)),
        transcripts = persistentMapOf(
            "chat" to persistentListOf(
                MessageUi.Prompt(
                    "prompt",
                    Instant.DISTANT_PAST,
                    "Помоги спланировать тренировки на неделю и сохранить результаты в ORBITA.",
                    isTimestampKnown = false,
                ),
                MessageUi.Reply(
                    "reply",
                    Instant.DISTANT_PAST,
                    """
                    На этой неделе запланированы три тренировки: А, Б и В. Две уже отмечены в журнале, следующая — в пятницу.

                    В ORBITA сохранены упражнения, рабочие веса и количество подходов. После занятия можно отметить его выполнение и сравнить результат с предыдущей неделей.

                    Программа рассчитана на восемь недель. Текущий этап и ближайшая тренировка доступны в сводке вверху чата; подробный план можно открыть из приложения.

                    Напиши, какие дни тебе удобны, и я помогу распределить оставшиеся занятия в календаре.
                    """.trimIndent(),
                    persistentListOf(),
                    false,
                    isTimestampKnown = false,
                ),
            ),
        ),
    )

    @Composable
    private fun Pane(
        state: AiStudioScreenState,
        slot: StudioEmbed,
        modifier: Modifier = Modifier,
        isCompact: Boolean = false,
        onIntent: (StudioIntent) -> Unit = {},
    ) {
        HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
            StudioPaneView(
                state.paneContent(state.panes.single()),
                onIntent,
                PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = isCompact),
                modifier.fillMaxSize(),
                slots = persistentMapOf(studioContextSlotKey(0) to slot),
            )
        }
    }

    private fun state(sessionId: String = "chat") = AiStudioScreenState(
        phase = StudioPhase.Ready,
        models = StudioModelOptions,
        panes = persistentListOf(PaneUi(0, sessionId = sessionId)),
        sessions = persistentListOf(SessionUi(sessionId, "Chat", null, Instant.fromEpochSeconds(1))),
        transcripts = persistentMapOf(
            sessionId to persistentListOf(
                MessageUi.Reply("r", Instant.fromEpochSeconds(2), "Conversation", persistentListOf(), false),
            ),
        ),
        contextPanels = persistentMapOf(0 to StudioContextPanelUi(sessionId, null)),
    )

    private fun richState() = state().copy(
        sessions = persistentListOf(SessionUi("chat", "Chat", "sport", Instant.fromEpochSeconds(1))),
        projects = persistentListOf(ProjectUi("sport", "Спорт", EnvironmentUi.Local, "master")),
        harnessChoices = HarnessChoicesUi(
            options = persistentListOf(HarnessOptionUi("gym", "Тренировки")),
            chats = persistentMapOf("chat" to persistentSetOf("gym")),
        ),
        contexts = persistentMapOf("chat" to ContextUsageUi(1300, 10000, 13)),
        apps = persistentMapOf(0 to StudioAppUi("harness:gym:app", "ORBITA", "gym", "chat", null)),
    )

    /** Representative native sections; service-ui tests exercise the actual spec renderer separately. */
    private object ReferenceSlot : StudioEmbed {
        @Composable
        override fun Content(modifier: Modifier) {
            HbColumn(
                modifier.fillMaxWidth().padding(HbTheme.spacing.l),
                gap = HbTheme.spacing.l,
            ) {
                HbText("Orbit Gym", color = HbTheme.colors.textSecondary)
                Section("Сегодня", "Тренировка А" to "60–90 мин", "Прогресс" to "2 из 6")
                HbDivider()
                Section("Эта неделя", "Тренировки" to "2 из 3", "Общий объём" to "4 800 кг")
                HbDivider()
                Section("Программа", "Следующая тренировка" to "Пятница", "Длительность" to "8 недель")
                HbButton("Продолжить тренировку", {}, Modifier.fillMaxWidth())
            }
        }

        @Composable
        private fun Section(title: String, vararg rows: Pair<String, String>) {
            HbColumn(gap = HbTheme.spacing.s) {
                HbText(title, color = HbTheme.colors.textSecondary)
                for ((label, value) in rows) {
                    HbRow(Modifier.fillMaxWidth()) {
                        HbText(label, Modifier.weight(1f), maxLines = 1)
                        HbText(value, color = HbTheme.colors.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }

    private class ContextSlot(private val height: Dp = 120.dp) : StudioEmbed {
        var mounted = 0
        var maxMounted = 0
        var mounts = 0
        var dismissRequest: (() -> Unit)? = null

        @Composable
        override fun Content(modifier: Modifier) {
            val dismiss = LocalHbSurfaceDismissRequest.current
            SideEffect { dismissRequest = dismiss }
            DisposableEffect(Unit) {
                mounted++
                mounts++
                maxMounted = maxOf(maxMounted, mounted)
                onDispose {
                    mounted--
                    dismissRequest = null
                }
            }
            Box(modifier) {
                Box(Modifier.height(height).testTag("context-body")) {
                    HbButton("Open", {}, Modifier.testTag("context-action"))
                }
            }
        }
    }
}
