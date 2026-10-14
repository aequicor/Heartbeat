package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioAppUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioAppKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioComposerSlotKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioStartSlotKey
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StudioAppWorkspaceUiTest {
    private val app = StudioAppUi("harness:gym:app", "Orbit Gym", "gym", null, null, persistentSetOf("gym"))
    private val initial = AiStudioScreenState(
        phase = StudioPhase.Ready,
        panes = persistentListOf(PaneUi(0)),
        apps = persistentMapOf(0 to app),
        harnessChoices = HarnessChoicesUi(
            options = persistentListOf(HarnessOptionUi("gym", "Orbit Gym"), HarnessOptionUi("notes", "Notes")),
            panes = persistentMapOf(0 to persistentSetOf("gym")),
        ),
    )

    @Test
    fun `the start app owns the body while composer and unrelated slots remain visible`() = runSkikoComposeUiTest(
        size = Size(1200f, 900f),
    ) {
        val slots = persistentMapOf(
            studioAppKey(0) to App(isStart = { true }),
            studioStartSlotKey(0) to Label("unrelated-start"),
            studioComposerSlotKey(0) to Label("unrelated-composer"),
        )
        setContent {
            HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
                StudioPaneView(
                    initial.paneContent(initial.panes.single()),
                    {},
                    layout,
                    Modifier.fillMaxSize(),
                    slots = slots,
                )
            }
        }
        val body = onNodeWithTag("app-body").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = onNodeWithTag("pane-footer-0").fetchSemanticsNode().boundsInRoot
        assertTrue(body.height > 400f, "The app receives the workspace, not a small composer slot")
        assertTrue(body.bottom <= footer.top, "The composer must not overlap the app")
        onNodeWithTag("new-session-hero").assertDoesNotExist()
        onNodeWithTag("unrelated-start").assertIsDisplayed()
        onNodeWithTag("unrelated-composer").assertIsDisplayed()
        onNodeWithTag("composer-0").assertIsDisplayed()
    }

    @Test
    fun `one native chip opens the app and its compact picker still offers every harness`() = runSkikoComposeUiTest(
        size = Size(900f, 700f),
    ) {
        val sent = mutableListOf<AiStudioScreenIntent>()
        setContent {
            HbTheme(darkTheme = true, dimensions = HbDimensions.Desktop) {
                StudioComposer(initial.paneContent(initial.panes.single()), sent::add, isCompact = false)
            }
        }
        onAllNodesWithText("Orbit Gym").assertCountEquals(1)
        onNodeWithTag("app-chip-0").performClick()
        assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.OpenApp(0, app.surfaceId)), sent)
        onNodeWithTag("harness-picker-0").performClick()
        onNodeWithText("Notes").assertIsDisplayed().performClick()
        assertEquals(AiStudioScreenIntent.SelectHarness(0, "notes", true), sent.last())
    }

    @Test
    fun `the same app host stays mounted when a new chat becomes a transcript`() = runSkikoComposeUiTest(
        size = Size(1200f, 900f),
    ) {
        var state by mutableStateOf(initial)
        var mounts = 0
        var disposals = 0
        val host = App(
            isStart = { state.panes.single().sessionId == null },
            onMount = { mounts++ },
            onDispose = { disposals++ },
        )
        val slots = persistentMapOf(studioAppKey(0) to host)
        setContent {
            HbTheme(dimensions = HbDimensions.Desktop) {
                StudioPaneView(
                    state.paneContent(state.panes.single()),
                    {},
                    layout,
                    Modifier.fillMaxSize(),
                    slots = slots,
                )
            }
        }
        onNodeWithTag("app-body").assertIsDisplayed()
        runOnIdle {
            state = state.copy(
                panes = persistentListOf(PaneUi(0, sessionId = "chat")),
                sessions = persistentListOf(SessionUi("chat", "Chat", null, Instant.DISTANT_PAST)),
                apps = persistentMapOf(0 to app.copy(sessionId = "chat", selectedHarnesses = persistentSetOf())),
            )
        }
        onNodeWithTag("app-body").assertDoesNotExist()
        onNodeWithTag("app-chip-0").assertIsDisplayed()
        assertEquals(1, mounts)
        assertEquals(0, disposals)
    }

    private class App(
        private val isStart: () -> Boolean,
        private val onMount: () -> Unit = {},
        private val onDispose: () -> Unit = {},
    ) : StudioEmbed {
        @Composable
        override fun Content(modifier: Modifier) {
            DisposableEffect(Unit) {
                onMount()
                onDispose { onDispose() }
            }
            if (isStart()) Box(modifier.testTag("app-body")) { HbText("Full application") }
        }
    }

    private class Label(private val name: String) : StudioEmbed {
        @Composable
        override fun Content(modifier: Modifier) = HbText(name, modifier.testTag(name))
    }

    private val layout = PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = false)
}
