package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbUnifiedMessageUiTest {
    private val tool = HbToolCall(
        "read",
        "Read files",
        blocks = persistentListOf(HbToolBlock.Console("result", "File contents")),
    )
    private val reasoning = HbToolCall(
        "reason",
        "Exposed reasoning",
        blocks = persistentListOf(HbToolBlock.Markdown("reasoning", "Compare the API")),
        kind = HbToolKind.Reasoning,
    )

    @Test
    fun `one answer owns one author and footer while nested reasoning and results expand in order`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            val expansion = HbToolExpansionState()
            val message = message(tool)
            val timeline = HbChatTimeline.from(HbChatSection("day", "Today", isDate = true), persistentListOf(message))
            setContent {
                HbTheme(darkTheme = false) {
                    HbChatTranscript(timeline, Modifier.fillMaxSize(), toolExpansionState = expansion)
                }
            }
            onNodeWithText("Today").assertIsDisplayed()
            onAllNodesWithText("Heartbeat").assertCountEquals(1)
            onAllNodesWithTag("message-footer:reply").assertCountEquals(1)
            onNodeWithText("Compare the API").assertDoesNotExist()
            onNodeWithText("File contents").assertDoesNotExist()
            onNodeWithText("Exposed reasoning").performClick()
            onNodeWithText("Read files").performClick()
            onNodeWithText("Compare the API").assertIsDisplayed()
            onNodeWithText("File contents").assertIsDisplayed()
            onAllNodesWithText("Heartbeat").assertCountEquals(1)
            onAllNodesWithTag("message-footer:reply").assertCountEquals(1)
            val intro = onNodeWithText("Before inspection").fetchSemanticsNode().boundsInRoot
            val thought = onNodeWithText("Compare the API").fetchSemanticsNode().boundsInRoot
            val output = onNodeWithText("File contents").fetchSemanticsNode().boundsInRoot
            val final = onNodeWithText("After inspection").fetchSemanticsNode().boundsInRoot
            assertTrue(intro.top < thought.top && thought.top < output.top && output.top < final.top)
            val author = onNodeWithText("Heartbeat").fetchSemanticsNode().boundsInRoot
            val footer = onNodeWithTag("message-footer:reply").fetchSemanticsNode().boundsInRoot
            assertEquals(author.left, intro.left)
            assertEquals(intro.left, footer.left)
            onNodeWithText("Read files").performClick()
            onNodeWithText("File contents").assertDoesNotExist()
            assertTrue(expansion.isExpanded("reply", "reason"))
        }

    @Test
    fun `compact answer keeps the full prose width below its author row`() =
        runSkikoComposeUiTest(size = Size(390f, 844f)) {
            val message = message(tool).copy(text = "Compact prose", parts = persistentListOf())
            setContent {
                HbTheme(darkTheme = false) {
                    HbChatMessageBubble(message)
                }
            }
            val prose = onNodeWithText("Compact prose").fetchSemanticsNode().boundsInRoot
            val author = onNodeWithText("Heartbeat").fetchSemanticsNode().boundsInRoot
            val header = onNodeWithTag("message-header:reply").fetchSemanticsNode().boundsInRoot
            val footer = onNodeWithTag("message-footer:reply").fetchSemanticsNode().boundsInRoot
            assertEquals(header.left, prose.left)
            assertEquals(prose.left, footer.left)
            assertTrue(prose.left < author.left)
        }

    @Test
    fun `large nested output remains lazy and streamed final prose preserves the reading anchor`() =
        runSkikoComposeUiTest(size = Size(640f, 440f)) {
            val largeTool = tool.copy(
                blocks = persistentListOf(HbToolBlock.Console("result", "output line\n".repeat(12000))),
            )
            val original = message(largeTool)
            var timeline by mutableStateOf(
                HbChatTimeline.from(HbChatSection("unknown", ""), persistentListOf(original)),
            )
            val state = LazyListState()
            val expansion = HbToolExpansionState().apply { setExpanded("reply", "read", true) }
            var composed = 0
            setContent {
                HbTheme(darkTheme = false) {
                    HbChatTranscript(
                        timeline,
                        Modifier.fillMaxSize(),
                        state = state,
                        toolExpansionState = expansion,
                        messageAppearance = {
                            composed++
                            it.appearance
                        },
                    )
                }
            }
            onAllNodes(hasScrollToIndexAction()).assertCountEquals(1)
            runOnIdle {
                assertTrue(state.layoutInfo.totalItemsCount > 300)
                assertTrue(composed < 100)
            }
            onNode(hasScrollToIndexAction()).performScrollToIndex(80)
            val anchor = runOnIdle {
                state.layoutInfo.visibleItemsInfo.first().key to state.firstVisibleItemScrollOffset
            }
            runOnIdle {
                timeline = timeline.replaceLatest(
                    original.copy(
                        parts = original.parts.mapIndexed { index, part ->
                            if (index == 3) HbMessagePart.Text("final", "Streaming final text") else part
                        }.toImmutableList(),
                    ),
                )
            }
            runOnIdle {
                assertEquals(
                    anchor,
                    state.layoutInfo.visibleItemsInfo.first().key to state.firstVisibleItemScrollOffset,
                )
                assertTrue(expansion.isExpanded("reply", "read"))
            }
        }

    private fun message(call: HbToolCall) = HbChatMessage(
        id = "reply",
        author = "Heartbeat",
        text = "Before inspection\n\nAfter inspection",
        appearance = HbMessageAppearance(isUnified = true, widthFraction = 1f),
        parts = persistentListOf(
            HbMessagePart.Text("intro", "Before inspection"),
            HbMessagePart.Tool(reasoning),
            HbMessagePart.Tool(call),
            HbMessagePart.Text("final", "After inspection"),
        ),
    )
}
