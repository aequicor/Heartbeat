package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.hbSurface
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioContextPanelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioContextSlotKey
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StudioContextPanelUiTest {
    @Test
    fun `a wide light pane places the context beside the conversation and sizes it to content`() = wide(false)

    @Test
    fun `a wide dark pane places the context beside the conversation and sizes it to content`() = wide(true)

    private fun wide(isDark: Boolean) = runSkikoComposeUiTest(size = Size(1200f, 820f)) {
        val slot = ContextSlot()
        setContent { Pane(state(), slot, isDark = isDark) }
        val panel = onNodeWithTag("context-body").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = onNodeWithTag("pane-header-0").fetchSemanticsNode().boundsInRoot
        val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
        val transcript = onNodeWithTag("transcript-chat").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.left >= transcript.right, "The panel must not overlap the conversation")
        assertTrue(panel.left >= composer.right, "The panel must not overlap the input")
        assertTrue(panel.top >= header.bottom, "The titlebar remains unobstructed")
        assertEquals(120f, panel.height, "A short panel does not fill the viewport")
        assertTrue(panel.bottom < composer.top)
        onNodeWithTag("pane-open-context-0").assertDoesNotExist()
        assertEquals(1, slot.mounted)
    }

    @Test
    fun `an existing but empty context host reserves no width or header action`() = runSkikoComposeUiTest(
        size = Size(1000f, 820f),
    ) {
        var available by mutableStateOf(false)
        val slot = ContextSlot()
        setContent {
            Pane(
                state().copy(contextPanels = if (available) state().contextPanels else persistentMapOf()),
                slot,
            )
        }
        val full = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.width
        assertEquals(0, slot.mounted)
        onNodeWithTag("pane-open-context-0").assertDoesNotExist()
        available = true
        waitForIdle()
        assertTrue(onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.width < full)
        available = false
        waitForIdle()
        assertEquals(full, onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot.width)
        assertEquals(0, slot.mounted)
    }

    @Test
    fun `a narrow panel opens once and Escape restores its header trigger`() = runSkikoComposeUiTest(
        size = Size(420f, 820f),
    ) {
        val slot = ContextSlot()
        setContent { Pane(state(), slot) }
        assertEquals(0, slot.mounted)
        onNodeWithTag("pane-open-context-0").assertIsDisplayed().performClick()
        onNodeWithTag("context-action").assertIsDisplayed()
        assertEquals(1, slot.mounted)
        onNodeWithTag("context-action").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(0, slot.mounted)
        onNodeWithTag("pane-open-context-0").assertIsFocused()
    }

    @Test
    fun `resizing an open dialog docks its single retained context host`() = runSkikoComposeUiTest(
        size = Size(1200f, 820f),
    ) {
        val slot = ContextSlot()
        var width by mutableStateOf(420.dp)
        setContent { Pane(state(), slot, modifier = Modifier.width(width)) }
        onNodeWithTag("pane-open-context-0").performClick()
        onNodeWithTag("context-action").assertIsDisplayed()
        width = 1200.dp
        waitForIdle()
        onNodeWithTag("pane-context-dialog-0").assertDoesNotExist()
        onNodeWithTag("pane-context-panel-0").assertIsDisplayed()
        assertEquals(1, slot.mounted)
        assertEquals(1, slot.maxMounted, "The responsive transition must never mount two copies")
    }

    @Test
    fun `reference context sections render with the full studio at wide and compact widths`() {
        for (isDark in listOf(false, true)) {
            for (width in listOf(1280f, 420f)) reference(width, isDark)
        }
    }

    private fun reference(width: Float, isDark: Boolean) = runSkikoComposeUiTest(size = Size(width, 900f)) {
        setContent {
            HbTheme(darkTheme = isDark, dimensions = HbDimensions.Desktop) {
                AiStudioContent(
                    state(),
                    {},
                    StudioExits(slots = persistentMapOf(studioContextSlotKey(0) to ReferenceSlot)),
                )
            }
        }
        if (width < 1000f) onNodeWithTag("pane-open-context-0").performClick()
        val panelTag = if (width < 1000f) "pane-context-dialog-0" else "pane-context-panel-0"
        onNodeWithTag(panelTag).assertIsDisplayed()
        val directory = File("build/reports/ai-studio/context-panel").apply { mkdirs() }
        val file = File(directory, "studio-${width.toInt()}-${if (isDark) "dark" else "light"}.png")
        check(ImageIO.write(captureToImage().toAwtImage(), "png", file))
    }

    @Composable
    private fun Pane(
        state: AiStudioScreenState,
        slot: StudioEmbed,
        modifier: Modifier = Modifier,
        isDark: Boolean = false,
    ) {
        HbTheme(darkTheme = isDark, dimensions = HbDimensions.Desktop) {
            StudioPaneView(
                state.paneContent(state.panes.single()),
                {},
                PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = false),
                modifier.fillMaxSize(),
                slots = persistentMapOf(studioContextSlotKey(0) to slot),
            )
        }
    }

    private fun state() = AiStudioScreenState(
        phase = StudioPhase.Ready,
        panes = persistentListOf(PaneUi(0, sessionId = "chat")),
        sessions = persistentListOf(SessionUi("chat", "Chat", null, Instant.fromEpochSeconds(1))),
        transcripts = persistentMapOf(
            "chat" to persistentListOf(
                MessageUi.Reply("r", Instant.fromEpochSeconds(2), "Conversation", persistentListOf(), false),
            ),
        ),
        contextPanels = persistentMapOf(0 to StudioContextPanelUi("chat", null)),
    )

    /** Representative native sections; service-ui tests exercise the actual spec renderer separately. */
    private object ReferenceSlot : StudioEmbed {
        @Composable
        override fun Content(modifier: Modifier) {
            HbColumn(
                modifier.fillMaxWidth().hbSurface(HbTheme.colors.surface, HbTheme.shapes.large)
                    .padding(HbTheme.spacing.l),
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

    private class ContextSlot : StudioEmbed {
        var mounted = 0
        var maxMounted = 0

        @Composable
        override fun Content(modifier: Modifier) {
            DisposableEffect(Unit) {
                mounted++
                maxMounted = maxOf(maxMounted, mounted)
                onDispose { mounted-- }
            }
            Box(modifier) {
                Box(Modifier.height(120.dp).testTag("context-body")) {
                    HbButton("Open", {}, Modifier.testTag("context-action"))
                }
            }
        }
    }
}
