package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbChatTranscriptUiTest {
    @Test
    fun `reduced motion jumps to latest without an active scroll animation`() = runSkikoComposeUiTest(
        size = Size(480f, 360f),
    ) {
        val listState = LazyListState(firstVisibleItemIndex = 8)
        val messages = (0 until 40).map { index ->
            HbChatMessage("message-$index", "Agent", "Message $index\nA second line\nA third line")
        }.toImmutableList()
        setContent {
            HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
                HbChatTranscript(messages = messages, state = listState, jumpToLatestLabel = "Latest")
            }
        }
        mainClock.autoAdvance = false
        onNodeWithText("Latest").performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
            assertFalse(listState.isScrollInProgress)
        }
    }

    @Test
    fun `stream follows bottom pauses for history and resumes after explicit jump`() = runSkikoComposeUiTest(
        size = Size(480f, 360f),
    ) {
        val listState = LazyListState()
        var messages by mutableStateOf(
            (0 until 40).map { index ->
                HbChatMessage("message-$index", "Agent", "Message $index\nA second line\nA third line")
            }.toImmutableList(),
        )
        setContent {
            HbTheme(darkTheme = false) {
                HbChatTranscript(messages = messages, state = listState, jumpToLatestLabel = "Latest")
            }
        }
        runOnIdle {
            messages = appendLastMessage(messages, "\nFirst streamed chunk")
        }
        runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }

        onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        val historyPosition = runOnIdle {
            assertTrue(listState.firstVisibleItemIndex > 0, "Swipe must expose older messages")
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
        runOnIdle {
            messages = appendLastMessage(messages, "\nAn additional streamed line".repeat(50))
        }
        runOnIdle {
            assertEquals(historyPosition.first, listState.firstVisibleItemIndex)
            assertEquals(historyPosition.second, listState.firstVisibleItemScrollOffset)
        }

        onNodeWithText("Latest").performClick()
        runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
            messages = appendLastMessage(messages, "\nFinal chunk")
        }
        runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
    }

    private fun appendLastMessage(messages: ImmutableList<HbChatMessage>, chunk: String): ImmutableList<HbChatMessage> =
        messages.mapIndexed { index, message ->
            if (index == messages.lastIndex) message.copy(text = message.text + chunk) else message
        }.toImmutableList()
}
