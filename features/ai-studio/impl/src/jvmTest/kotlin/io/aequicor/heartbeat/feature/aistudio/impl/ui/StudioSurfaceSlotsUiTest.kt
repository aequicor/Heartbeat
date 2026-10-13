package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.studioComposerSlotKey
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.studioStartSlotKey
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioPhase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StudioSurfaceSlotsUiTest {
    private val slots = persistentMapOf(
        studioComposerSlotKey(0) to Slot("composer"),
        studioStartSlotKey(0) to Slot("start"),
    )

    @Test
    fun `a new chat draws its interface slots above the input and in its start window`() = runSkikoComposeUiTest(
        size = Size(1200f, 900f),
    ) {
        val state = AiStudioScreenState(phase = StudioPhase.Ready, panes = persistentListOf(PaneUi(0)))
        setContent {
            HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
                StudioPaneView(
                    state.paneContent(state.panes.single()),
                    {},
                    PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = false),
                    Modifier.fillMaxSize(),
                    slots = slots,
                )
            }
        }

        val composer = onNodeWithTag("slot-composer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val input = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
        assertTrue(composer.bottom <= input.top, "the composer slot sits above the input")
        onNodeWithTag("slot-start").assertIsDisplayed()
    }

    @Test
    fun `a chat draws only its composer slot`() = runSkikoComposeUiTest(size = Size(400f, 820f)) {
        val state = AiStudioScreenState(
            phase = StudioPhase.Ready,
            panes = persistentListOf(PaneUi(0, sessionId = "chat")),
            sessions = persistentListOf(SessionUi("chat", "Chat", null, Instant.fromEpochSeconds(1))),
        )
        setContent {
            HbTheme(darkTheme = true) {
                StudioPaneView(
                    state.paneContent(state.panes.single()),
                    {},
                    PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = true),
                    Modifier.fillMaxSize(),
                    slots = slots,
                )
            }
        }

        onNodeWithTag("slot-composer").assertIsDisplayed()
        onNodeWithTag("slot-start").assertDoesNotExist()
    }

    private class Slot(private val name: String) : ComposableComponent {
        @Composable
        override fun Content(modifier: Modifier) {
            HbText(name, modifier.testTag("slot-$name"))
        }
    }
}
