package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbSectionedTranscriptUiTest {
    @Test
    fun `one very long agent message composes bounded chunks in the transcript list`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        var appearanceCalls = 0
        val timeline = HbChatTimeline.Empty.append(
            HbChatSection("long", "Long response"),
            HbChatMessage("long-answer", "Agent", "A long agent response. ".repeat(20000)),
        )
        val state = LazyListState(firstVisibleItemIndex = 50)
        setContent {
            HbTheme(darkTheme = false) {
                HbChatTranscript(
                    timeline,
                    state = state,
                    messageAppearance = {
                        appearanceCalls++
                        it.appearance
                    },
                )
            }
        }
        runOnIdle {
            assertTrue(state.layoutInfo.totalItemsCount > 100)
            assertTrue(appearanceCalls < 40, "Long responses must virtualize their chunks, composed $appearanceCalls")
        }
    }

    @Test
    fun `ten thousand messages compose only viewport rows and tail updates retain history`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        val seen = mutableSetOf<String>()
        var timeline by mutableStateOf(HbChatTimeline.from(HbChatSection("large", "Large session"), messages(0, 10000)))
        val state = LazyListState(firstVisibleItemIndex = 5000)
        setContent {
            HbTheme(darkTheme = false) {
                HbChatTranscript(
                    timeline = timeline,
                    state = state,
                    messageAppearance = { message ->
                        seen.add(message.id)
                        message.appearance
                    },
                )
            }
        }
        val originalAnchor = runOnIdle {
            assertEquals(10001, state.layoutInfo.totalItemsCount)
            assertTrue(seen.size < 60, "Only visible/prefetched rows should compose, got ${seen.size}")
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }
        repeat(8) { token ->
            runOnIdle {
                timeline = timeline.replaceLatest(
                    timeline.latestMessage!!.copy(text = "Streaming token $token"),
                )
            }
        }
        runOnIdle {
            assertEquals(originalAnchor, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
            assertTrue(seen.size < 60, "A tail update must not compose the whole history")
        }
    }

    @Test
    fun `section header pins at the top and next section replaces it`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        val today = HbChatSection("today", "Today")
        val timeline = messages(
            30,
            30,
        ).fold(HbChatTimeline.from(HbChatSection("yesterday", "Yesterday"), messages(0, 30))) { current, message ->
            current.append(today, message)
        }
        val state = LazyListState(firstVisibleItemIndex = 8)
        setContent { HbTheme(darkTheme = false) { HbChatTranscript(timeline, state = state) } }
        onNodeWithText("Yesterday").assertIsDisplayed()
        val pinnedTop = onNodeWithText("Yesterday").fetchSemanticsNode().boundsInRoot.top
        onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        assertTrue(abs(onNodeWithText("Yesterday").fetchSemanticsNode().boundsInRoot.top - pinnedTop) < 2f)
        onNode(hasScrollToIndexAction()).performScrollToIndex(38)
        onNodeWithText("Today").assertIsDisplayed()
        assertTrue(abs(onNodeWithText("Today").fetchSemanticsNode().boundsInRoot.top - pinnedTop) < 2f)
    }

    @Test
    fun `history prepend preserves keyed reader position and jump resumes follow`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        var timeline by mutableStateOf(HbChatTimeline.from(HbChatSection("today", "Today"), messages(100, 60)))
        val state = LazyListState(firstVisibleItemIndex = 25, firstVisibleItemScrollOffset = 12)
        setContent {
            HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
                HbChatTranscript(timeline, state = state, jumpToLatestLabel = "Latest")
            }
        }
        val anchor = runOnIdle {
            state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key to
                state.firstVisibleItemScrollOffset
        }
        runOnIdle { timeline = timeline.prepend(HbChatSection("older", "Earlier session"), messages(0, 100)) }
        runOnIdle {
            assertEquals(
                anchor.first,
                state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key,
            )
            assertEquals(anchor.second, state.firstVisibleItemScrollOffset)
        }
        onNodeWithText("Latest").performClick()
        runOnIdle {
            assertFalse(state.canScrollForward)
            timeline = timeline.replaceLatest(timeline.latestMessage!!.copy(text = "A streamed line\n".repeat(100)))
        }
        runOnIdle { assertFalse(state.canScrollForward, "Growing/chunked latest content must remain followed") }
    }

    @Test
    fun `replacing or clearing history removes the previously pinned section`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        var timeline by mutableStateOf(HbChatTimeline.from(HbChatSection("old", "Old session"), messages(0, 40)))
        val state = LazyListState(firstVisibleItemIndex = 10)
        setContent {
            HbTheme(darkTheme = false) { HbChatTranscript(timeline, state = state) }
        }
        onNodeWithText("Old session").assertIsDisplayed()
        runOnIdle {
            timeline = HbChatTimeline.from(HbChatSection("new", "New session"), messages(100, 2))
        }
        onNodeWithText("Old session").assertDoesNotExist()
        onNodeWithText("New session").assertIsDisplayed()
        runOnIdle { timeline = HbChatTimeline.Empty }
        onNodeWithText("New session").assertDoesNotExist()
    }

    private fun messages(start: Int, count: Int) = (start until start + count).map {
        HbChatMessage("message-$it", "Agent", "Message $it\nA second line\nA third line")
    }.toImmutableList()
}
