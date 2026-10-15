package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
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
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioContextPanelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioAppKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioComposerSlotKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioContextSlotKey
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
            options = persistentListOf(HarnessOptionUi("gym", "Training program"), HarnessOptionUi("notes", "Notes")),
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
    fun `the selected harness keeps its name beside the launcher unless the composer is narrow`() {
        for (width in listOf(420f, 900f)) checkHarnessLabel(width)
    }

    private fun checkHarnessLabel(width: Float) = runSkikoComposeUiTest(size = Size(width, 700f)) {
        val sent = mutableListOf<AiStudioScreenIntent>()
        setContent {
            HbTheme(darkTheme = true, dimensions = HbDimensions.Desktop) {
                StudioComposer(initial.paneContent(initial.panes.single()), sent::add, isCompact = false)
            }
        }
        onAllNodesWithText("Orbit Gym").assertCountEquals(1)
        if (width > 420f) {
            onNodeWithText("Training program").assertIsDisplayed()
        } else {
            onNodeWithText("Training program").assertDoesNotExist()
        }
        val picker = onNodeWithTag("harness-picker-0").fetchSemanticsNode().boundsInRoot
        val launcher = onNodeWithTag("app-chip-0").fetchSemanticsNode().boundsInRoot
        assertTrue(picker.right <= launcher.left || picker.bottom <= launcher.top)
        assertTrue(picker.left >= 0 && launcher.right <= width)
        onNodeWithTag("app-chip-0").performClick()
        assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.OpenApp(0, app.surfaceId)), sent)
        onNodeWithTag("harness-picker-0").performClick()
        onNodeWithText("Notes").assertIsDisplayed().performClick()
        assertEquals(AiStudioScreenIntent.SelectHarness(0, "notes", true), sent.last())
    }

    @Test
    fun `the start app has matching outer gutters without adding space above the document`() {
        for (width in listOf(420f, 1280f)) {
            for (dark in listOf(false, true)) checkStartGutters(width, dark)
        }
    }

    private fun checkStartGutters(width: Float, dark: Boolean) = runSkikoComposeUiTest(size = Size(width, 900f)) {
        val state = initial.copy(
            contextPanels = persistentMapOf(0 to StudioContextPanelUi(null, null, persistentSetOf("gym"))),
        )
        var gutter = 0f
        val compact = width == 420f
        setContent {
            HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) {
                val spacing = HbTheme.spacing
                val density = LocalDensity.current
                SideEffect { gutter = with(density) { (if (compact) spacing.m else spacing.xl).toPx() } }
                StudioPaneView(
                    state.paneContent(state.panes.single()),
                    {},
                    layout.copy(isCompact = compact),
                    Modifier.fillMaxSize(),
                    slots = persistentMapOf(
                        studioAppKey(0) to App(isStart = { true }),
                        studioContextSlotKey(0) to Label("context-body"),
                    ),
                )
            }
        }
        val pane = onNodeWithTag("pane-0").fetchSemanticsNode().boundsInRoot
        val body = onNodeWithTag("app-body").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = onNodeWithTag("pane-header-0").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("pane-open-context-0").assertIsDisplayed()
        onNodeWithTag("context-body").assertDoesNotExist()
        assertEquals(gutter, body.left - pane.left, 1f)
        assertEquals(gutter, pane.right - body.right, 1f)
        assertEquals(header.bottom, body.top, 1f, "The gutter must not reintroduce a header gap")
    }

    @Test
    fun `an open start summary reserves only its height and keeps the app mounted and composer unchanged`() =
        runSkikoComposeUiTest(size = Size(2000f, 900f)) {
            val state = initial.copy(
                contextPanels = persistentMapOf(0 to StudioContextPanelUi(null, null, persistentSetOf("gym"))),
            )
            var mounts = 0
            val appHost = App(isStart = { true }, onMount = { mounts++ })
            setContent {
                HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
                    StudioPaneView(
                        state.paneContent(state.panes.single()),
                        {},
                        layout,
                        Modifier.fillMaxSize(),
                        slots = persistentMapOf(
                            studioAppKey(0) to appHost,
                            studioContextSlotKey(0) to Label("context-body"),
                        ),
                    )
                }
            }
            val originalApp = onNodeWithTag("app-body").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
            onNodeWithTag("context-body").assertDoesNotExist()
            onNodeWithTag("pane-open-context-0").performClick()
            val panel = onNodeWithTag("pane-context-panel-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val openedApp = onNodeWithTag("app-body").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(openedApp.top >= panel.bottom, "A native start document must not overlap the summary")
            assertEquals(originalApp.width, openedApp.width, "The start document keeps the full available width")
            assertEquals(composer, onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot)
            assertEquals(1, mounts)
            onNodeWithTag("pane-open-context-0").performClick()
            onNodeWithTag("context-body").assertDoesNotExist()
            assertEquals(originalApp, onNodeWithTag("app-body").fetchSemanticsNode().boundsInRoot)
            assertEquals(composer, onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot)
            assertEquals(1, mounts, "Opening and closing the summary must preserve the app host")
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
