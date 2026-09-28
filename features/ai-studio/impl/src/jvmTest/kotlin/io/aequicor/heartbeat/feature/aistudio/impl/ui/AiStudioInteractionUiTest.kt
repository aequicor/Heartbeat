package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarMode
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioModelOptions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.reduce
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraft
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_placeholder
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_stop
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_no_results
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class AiStudioInteractionUiTest {
    private val workspace = AiStudioScreenState(
        phase = StudioPhase.Ready,
        panes = persistentListOf(PaneUi(0, sessionId = "active")),
        models = StudioModelOptions,
        sessions = persistentListOf(
            SessionUi("active", "Studio active", null, Instant.fromEpochSeconds(3)),
            SessionUi("other", "Other topic", null, Instant.fromEpochSeconds(2)),
            SessionUi("archive", "Studio archive", null, Instant.fromEpochSeconds(1), isArchived = true),
        ),
    )
    private val exits = StudioExits(onBack = {}, onOpenToggles = {})

    @Test
    fun `floating header and composer block hidden tools while their own controls remain clickable`() =
        runSkikoComposeUiTest(size = Size(400f, 820f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val state = workspace.copy(
                running = persistentSetOf("active"),
                transcripts = persistentMapOf("active" to overlayToolMessages()),
            )
            var expandLabel = ""
            var collapseLabel = ""
            var stopLabel = ""
            setContent {
                val labels = studioToolLabels()
                expandLabel = labels.expand
                collapseLabel = labels.collapse
                stopLabel = stringResource(Res.string.composer_stop)
                HbTheme(darkTheme = false) { AiStudioContent(state, events::add, exits) }
            }
            val transcript = onNode(hasScrollToIndexAction())
            val tool = onNodeWithText("Inspect step 30")
            val pane = onNodeWithTag("pane-0")
            val header = onNodeWithText("Studio active").fetchSemanticsNode().boundsInRoot
            val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
            val overlayTargets = listOf(header to header.center.y, composer to composer.top + 4f)
            overlayTargets.forEach { (overlay, targetY) ->
                transcript.performScrollToNode(hasText("Inspect step 30"))
                val before = tool.fetchSemanticsNode().boundsInRoot
                transcript.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                    scroll(0f, before.center.y - targetY)
                }
                waitForIdle()
                val hiddenTool = tool.fetchSemanticsNode().boundsInRoot
                val point = Offset(
                    (maxOf(hiddenTool.left, overlay.left) + minOf(hiddenTool.right, overlay.right)) / 2,
                    targetY,
                )
                assertTrue(hiddenTool.contains(point), "The overlay click must overlap an actual tool control")
                assertTrue(overlay.contains(point), "The click must land inside the floating surface")
                val paneOrigin = pane.fetchSemanticsNode().boundsInRoot.topLeft
                pane.performMouseInput { click(point - paneOrigin) }
                tool.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expandLabel))
            }

            transcript.performScrollToNode(hasText("Inspect step 30"))
            val toolBeforeCentering = tool.fetchSemanticsNode().boundsInRoot
            transcript.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                scroll(0f, toolBeforeCentering.center.y - (header.bottom + composer.top) / 2)
            }
            waitForIdle()
            val visibleTool = tool.fetchSemanticsNode().boundsInRoot
            assertTrue(visibleTool.center.y > header.bottom && visibleTool.center.y < composer.top)
            tool.performMouseInput { click(center) }
            tool.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapseLabel))
            onNodeWithTag("pane-open-sidebar").performMouseInput { click(center) }
            onNodeWithContentDescription(stopLabel).performMouseInput { click(center) }
            runOnIdle {
                assertEquals(
                    listOf(AiStudioScreenIntent.SetDrawerOpen(true), AiStudioScreenIntent.Stop("active")),
                    events,
                )
            }
        }

    @Test
    fun `search keeps active and archived chats separate and opens its matching result`() =
        runSkikoComposeUiTest(size = Size(1280f, 900f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            var state by mutableStateOf(workspace)
            var noResultsLabel = ""
            setContent {
                noResultsLabel = stringResource(Res.string.sidebar_no_results)
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, { intent ->
                        events += intent
                        if (intent is AiStudioScreenIntent.Sidebar) {
                            state = state.copy(sidebar = state.sidebar.reduce(intent))
                        }
                    }, exits)
                }
            }
            onNodeWithTag("sidebar-search-open").performClick()
            onNodeWithTag("sidebar-search").performTextInput("Studio")
            onNodeWithTag("session-active").assertIsDisplayed().performClick()
            onNodeWithTag("session-other").assertDoesNotExist()
            onNodeWithTag("session-archive").assertDoesNotExist()

            onNodeWithTag("rail-archive").performClick()
            onNodeWithTag("session-archive").assertIsDisplayed()
            onNodeWithTag("session-active").assertDoesNotExist()
            onNodeWithTag("sidebar-search").performTextReplacement("Missing conversation")
            onNodeWithText(noResultsLabel).assertIsDisplayed()
            onNodeWithTag("session-archive").assertDoesNotExist()

            onNodeWithTag("rail-sessions").performClick()
            onNodeWithTag("sidebar-search").performTextReplacement("Studio")
            onNodeWithTag("session-active").assertIsDisplayed()
            runOnIdle {
                assertTrue(AiStudioScreenIntent.OpenSession("active") in events)
                assertEquals(SidebarMode.Workspace, state.sidebar.mode)
            }
        }

    @Test
    fun `typing and sending in the second pane keeps the first pane draft intact`() =
        runSkikoComposeUiTest(size = Size(1440f, 900f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            var sendLabel = ""
            var state by mutableStateOf(
                workspace.copy(
                    panes = persistentListOf(PaneUi(0, sessionId = "active"), PaneUi(1)),
                    focusedPaneId = 1,
                ).withDraft(0, "Keep the first draft"),
            )
            setContent {
                sendLabel = stringResource(Res.string.composer_send)
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, { intent ->
                        events += intent
                        if (intent is AiStudioScreenIntent.DraftChanged) {
                            state = state.withDraft(intent.paneId, intent.text)
                        }
                    }, exits)
                }
            }
            val composer = hasAnyAncestor(hasTestTag("composer-1"))
            onNode(composer and hasContentDescription(sendLabel)).assertIsNotEnabled()
            onNode(composer and hasSetTextAction()).performTextInput("Test the redesigned studio")
            onNode(composer and hasContentDescription(sendLabel)).assertIsEnabled().performClick()
            runOnIdle {
                assertEquals(
                    listOf<AiStudioScreenIntent>(
                        AiStudioScreenIntent.DraftChanged(1, "Test the redesigned studio"),
                        AiStudioScreenIntent.Submit(1),
                    ),
                    events,
                )
                assertEquals("Keep the first draft", state.draft(0))
                assertEquals("Test the redesigned studio", state.draft(1))
            }
        }

    @Test
    fun `running chat stops its session and disables the composer while stopping`() =
        runSkikoComposeUiTest(size = Size(1280f, 900f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            var sendLabel = ""
            var stopLabel = ""
            var inputLabel = ""
            var state by mutableStateOf(
                workspace.copy(running = persistentSetOf("active")).withDraft(0, "Keep this follow-up"),
            )
            setContent {
                sendLabel = stringResource(Res.string.composer_send)
                stopLabel = stringResource(Res.string.composer_stop)
                inputLabel = stringResource(Res.string.composer_placeholder)
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, { intent ->
                        events += intent
                        if (intent is AiStudioScreenIntent.Stop) {
                            state = state.copy(stopping = persistentSetOf(intent.sessionId))
                        }
                    }, exits)
                }
            }
            onNodeWithContentDescription(sendLabel).assertDoesNotExist()
            onNodeWithContentDescription(stopLabel).assertIsEnabled().performClick()
            onNodeWithContentDescription(stopLabel).assertIsNotEnabled()
            onNode(hasAnyAncestor(hasTestTag("composer-0")) and hasContentDescription(inputLabel))
                .assertIsNotEnabled()
            runOnIdle {
                assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.Stop("active")), events)
                assertEquals("Keep this follow-up", state.draft(0))
            }
        }

    @Test
    fun `compact layout follows the focused pane and restores each chat draft`() =
        runSkikoComposeUiTest(size = Size(400f, 820f)) {
            var state by mutableStateOf(
                workspace.copy(
                    panes = persistentListOf(PaneUi(0, sessionId = "active"), PaneUi(1, sessionId = "other")),
                    focusedPaneId = 1,
                ).withDraft(0, "First chat draft").withDraft(1, "Second chat draft"),
            )
            setContent { HbTheme(darkTheme = false) { AiStudioContent(state, {}, exits) } }
            onNodeWithTag("pane-0").assertDoesNotExist()
            onNodeWithTag("pane-1").assertIsDisplayed()
            onNodeWithTag("pane-split").assertDoesNotExist()
            onNode(hasAnyAncestor(hasTestTag("composer-1")) and hasSetTextAction())
                .assertTextContains("Second chat draft")
            runOnIdle { state = state.copy(focusedPaneId = 0) }
            onNodeWithTag("pane-1").assertDoesNotExist()
            onNodeWithTag("pane-0").assertIsDisplayed()
            onNode(hasAnyAncestor(hasTestTag("composer-0")) and hasSetTextAction())
                .assertTextContains("First chat draft")
        }

    @Test
    fun `split layout reserves the gap before showing two panes`() {
        listOf(983f to false, 984f to true).forEach { (width, isSplitAllowed) ->
            runSkikoComposeUiTest(size = Size(width, 900f)) {
                var state by mutableStateOf(
                    workspace.copy(
                        panes = persistentListOf(PaneUi(0, sessionId = "active"), PaneUi(1)),
                        focusedPaneId = 1,
                    ),
                )
                setContent { HbTheme(darkTheme = false) { AiStudioContent(state, {}, exits) } }
                onNodeWithTag("studio-sidebar").assertIsDisplayed()
                onNodeWithTag("pane-1").assertIsDisplayed()
                if (isSplitAllowed) {
                    onNodeWithTag("pane-0").assertIsDisplayed()
                    onNodeWithTag("pane-close-1").assertIsDisplayed()
                } else {
                    onNodeWithTag("pane-0").assertDoesNotExist()
                    onNodeWithTag("pane-close-1").assertDoesNotExist()
                }
                runOnIdle { state = state.copy(panes = persistentListOf(PaneUi(1))) }
                if (isSplitAllowed) {
                    onNodeWithTag("pane-split").assertIsDisplayed()
                } else {
                    onNodeWithTag("pane-split").assertDoesNotExist()
                }
            }
        }
    }

    private fun overlayToolMessages() = (0 until 80).map { index ->
        MessageUi.Reply(
            id = "reply-$index",
            createdAt = Instant.fromEpochSeconds(index.toLong()),
            text = "",
            tools = persistentListOf(
                ToolUi(
                    id = "tool-$index",
                    title = "Inspect step $index",
                    status = ToolStatusUi.Done,
                    output = "Inspection result $index",
                    diff = null,
                ),
            ),
            isStreaming = false,
        )
    }.toImmutableList()
}
