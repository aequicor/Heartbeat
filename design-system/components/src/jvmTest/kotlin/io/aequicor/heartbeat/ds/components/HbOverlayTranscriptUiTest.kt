package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbOverlayTranscriptUiTest {
    @Test
    fun `headerless transcript uses the full viewport and reserves measured overlay insets`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        val timeline = HbChatTimeline.from(HbChatSection("today", "Today"), overlayMessages(0, 80))
        val state = LazyListState(firstVisibleItemIndex = 10)
        var bottomPadding by mutableStateOf(100.dp)
        setContent {
            OverlayTranscriptTheme {
                HbChatTranscript(
                    timeline,
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    jumpToLatestLabel = "Latest",
                    contentPadding = PaddingValues(start = 12.dp, top = 80.dp, end = 12.dp, bottom = bottomPadding),
                    showSectionHeaders = false,
                )
            }
        }
        onNodeWithText("Today").assertDoesNotExist()
        runOnIdle {
            assertEquals(timeline.itemCount - 1, state.layoutInfo.totalItemsCount)
            assertEquals(420, state.layoutInfo.viewportSize.height)
            assertEquals(80, state.layoutInfo.beforeContentPadding)
            assertEquals(100, state.layoutInfo.afterContentPadding)
        }
        val jumpBottom = onNodeWithText("Latest").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(jumpBottom <= 320f, "The latest action must remain above the floating composer")
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        runOnIdle {
            val info = state.layoutInfo
            assertEquals(80, info.visibleItemsInfo.first().offset - info.viewportStartOffset)
        }
        onNodeWithText("Latest").performClick()
        runOnIdle {
            assertFalse(state.canScrollForward)
            bottomPadding = 140.dp
        }
        runOnIdle {
            assertFalse(state.canScrollForward, "Growing the composer must keep a followed tail visible")
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.last()
            val visibleBottom = last.offset - info.viewportStartOffset + last.size
            assertTrue(abs(visibleBottom - (420 - 140)) <= 1, "The latest row must clear the composer inset")
        }
    }

    @Test
    fun `removing section headers and prepending history retain the reader key and offset`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        var timeline by mutableStateOf(HbChatTimeline.from(HbChatSection("today", "Today"), overlayMessages(200, 80)))
        var showHeaders by mutableStateOf(true)
        val state = LazyListState(firstVisibleItemIndex = 24, firstVisibleItemScrollOffset = 12)
        setContent {
            OverlayTranscriptTheme {
                HbChatTranscript(timeline, state = state, showSectionHeaders = showHeaders)
            }
        }
        val anchor = runOnIdle { overlayReadingAnchor(state) }
        runOnIdle { showHeaders = false }
        onNodeWithText("Today").assertDoesNotExist()
        runOnIdle { assertEquals(anchor, overlayReadingAnchor(state)) }
        runOnIdle { timeline = timeline.prepend(HbChatSection("older", "Older"), overlayMessages(0, 200)) }
        runOnIdle {
            assertEquals(
                anchor,
                overlayReadingAnchor(state),
                "A prepend beyond the nearby-key window must retain position",
            )
            assertEquals(timeline.itemCount - 2, state.layoutInfo.totalItemsCount)
            showHeaders = true
        }
        runOnIdle { assertEquals(anchor, overlayReadingAnchor(state)) }
    }

    @Test
    fun `collapsing a large headerless tool output retains the same visible message`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        val tool = HbChatMessage(
            id = "tool",
            author = "Agent",
            text = "",
            toolCalls = persistentListOf(
                HbToolCall(
                    id = "inspect",
                    title = "Inspect workspace",
                    blocks = persistentListOf(
                        HbToolBlock.Console("console", (0 until 12000).joinToString("\n") { "Output line $it" }),
                    ),
                ),
            ),
        )
        val timeline = HbChatTimeline.from(
            HbChatSection("today", "Today"),
            (listOf(tool) + overlayMessages(0, 80)).toImmutableList(),
        )
        val state = LazyListState(firstVisibleItemIndex = 1)
        val expansion = HbToolExpansionState().apply { setExpanded("tool", "inspect", true) }
        var appearanceCalls = 0
        setContent {
            OverlayTranscriptTheme {
                HbChatTranscript(
                    timeline,
                    state = state,
                    showSectionHeaders = false,
                    toolExpansionState = expansion,
                    messageAppearance = { message ->
                        appearanceCalls++
                        message.appearance
                    },
                )
            }
        }
        val readerIndex = runOnIdle {
            assertTrue(state.layoutInfo.totalItemsCount > 400)
            assertTrue(appearanceCalls < 100, "Hidden headings must preserve payload virtualization")
            state.layoutInfo.totalItemsCount - 30
        }
        onNode(hasScrollToIndexAction()).performScrollToIndex(readerIndex)
        val anchor = runOnIdle { overlayReadingAnchor(state) }
        runOnIdle { expansion.setExpanded("tool", "inspect", false) }
        runOnIdle { assertEquals(anchor, overlayReadingAnchor(state)) }
    }
}

@Composable
private fun OverlayTranscriptTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true), content = content)
    }
}

private fun overlayMessages(start: Int, count: Int) = (start until start + count).map {
    HbChatMessage("message-$it", "Agent", "Message $it\nA second line\nA third line")
}.toImmutableList()

private fun overlayReadingAnchor(state: LazyListState): Pair<Any, Int> =
    state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key to
        state.firstVisibleItemScrollOffset
