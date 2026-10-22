package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ComposerSuggestionsUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SuggestionKindUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SuggestionOriginUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SuggestionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraftCaret
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Selection movement of the composer suggestions as the store publishes it: an owner-side state
 * change must move the highlight immediately, including arrow repeats faster than the hover animation.
 * Unchanged rows retain their position while the editor keeps focus.
 */
@OptIn(ExperimentalTestApi::class)
class StudioSuggestionsSelectionUiTest {
    @Test
    fun `rapid selection changes immediately highlight the selected row`() =
        runSkikoComposeUiTest(size = Size(1200f, 800f)) {
            val pane = PaneUi(0)
            var selectedFill = 0
            var surface = 0
            val base = AiStudioScreenState(panes = persistentListOf(pane), models = StudioModelOptions)
                .withDraft(pane.id, "/")
                .withDraftCaret(pane.id, 1)
            var state by mutableStateOf(base.copy(composerSuggestions = suggestions(base, pane.id, 0)))
            setContent {
                HbTheme(darkTheme = false) {
                    selectedFill = HbTheme.colors.selectedContainer.toArgb()
                    surface = HbTheme.colors.surface.toArgb()
                    Box(Modifier.fillMaxSize().padding(HbTheme.spacing.xl), contentAlignment = Alignment.BottomCenter) {
                        StudioComposer(state.paneContent(pane), {}, isCompact = false)
                    }
                }
            }
            onNode(hasSetTextAction()).performMouseInput { click() }
            waitForIdle()
            val moved = onNodeWithText(MOVED_LABEL)
            val bounds = moved.fetchSemanticsNode().boundsInWindow
            val firstTop = onNodeWithText(FIRST_LABEL).fetchSemanticsNode().boundsInWindow.top
            fun fill(): Int = captureToImage().toAwtImage().getRGB((bounds.right - 4).toInt(), bounds.center.y.toInt())

            assertEquals(surface, fill(), "an unselected row keeps the popup surface")

            mainClock.autoAdvance = false
            repeat(3) {
                state = state.copy(composerSuggestions = suggestions(state, pane.id, 1))
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeByFrame()
                assertEquals(selectedFill, fill(), "keyboard selection does not wait for hover animation")
                state = state.copy(composerSuggestions = suggestions(state, pane.id, 0))
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeByFrame()
                assertEquals(surface, fill(), "previous selection leaves no fading highlight")
            }
            mainClock.autoAdvance = true
            waitForIdle()

            assertEquals(firstTop, onNodeWithText(FIRST_LABEL).fetchSemanticsNode().boundsInWindow.top)
            moved.assertIsDisplayed()
        }

    private companion object {
        const val FIRST_LABEL = "Remember a lesson"
        const val MOVED_LABEL = "Verify the change"

        fun suggestions(state: AiStudioScreenState, paneId: Int, selected: Int) = persistentMapOf(
            state.draftKey(paneId) to ComposerSuggestionsUi(
                items = persistentListOf(
                    row("remember", FIRST_LABEL, "Save a durable instruction"),
                    row("verify", MOVED_LABEL, "Run applicable checks"),
                    row("utf8", "Read UTF-8 files", "Encoding helper"),
                    row("four", "Fourth command", "Description four"),
                    row("five", "Fifth command", "Description five"),
                    row("six", "Sixth command", "Description six"),
                    row("seven", "Seventh command", "Description seven"),
                    row("eight", "Eighth command", "Description eight"),
                ),
                selectedIndex = selected,
                token = "/",
                requestId = 1L,
            ),
        ).toImmutableMap()

        fun row(id: String, label: String, description: String) = SuggestionUi(
            id = id,
            label = label,
            description = description,
            kind = SuggestionKindUi.Command,
            origin = SuggestionOriginUi.Heartbeat,
            insert = label,
        )
    }
}
