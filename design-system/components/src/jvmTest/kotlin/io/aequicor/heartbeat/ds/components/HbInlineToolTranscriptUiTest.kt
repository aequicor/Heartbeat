package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbInlineToolTranscriptUiTest {
    @Test
    fun `twenty four thousand tool output lines share one bounded outer lazy list`() = runSkikoComposeUiTest(
        size = Size(640f, 440f),
    ) {
        val timeline = toolTimeline(outputLines = 12000)
        val state = LazyListState(firstVisibleItemIndex = 1)
        var appearanceCalls = 0
        setContent { InlineToolHost(timeline, state, onAppearance = { appearanceCalls++ }) }
        onNodeWithText("workspace.inspect").performClick()
        onAllNodes(hasScrollToIndexAction()).assertCountEquals(1)
        val expandedCount = runOnIdle {
            assertTrue(
                state.layoutInfo.totalItemsCount > 900,
                "Tool output must become independently lazy transcript rows",
            )
            assertTrue(appearanceCalls < 120, "Expanding output must not compose all its rows: $appearanceCalls")
            state.layoutInfo.totalItemsCount
        }
        onNode(hasScrollToIndexAction()).performScrollToIndex(expandedCount / 2)
        onAllNodes(hasScrollToIndexAction()).assertCountEquals(1)
        runOnIdle {
            assertTrue(
                appearanceCalls < 240,
                "Jumping into a large result must compose only nearby rows: $appearanceCalls",
            )
        }
    }

    @Test
    fun `wheel over expanded console output advances the outer transcript`() = runSkikoComposeUiTest(
        size = Size(640f, 440f),
    ) {
        val timeline = toolTimeline(outputLines = 1200)
        val state = LazyListState(firstVisibleItemIndex = 1)
        setContent { InlineToolHost(timeline, state) }
        onNodeWithText("workspace.inspect").performClick()
        onAllNodes(hasScrollToIndexAction()).assertCountEquals(1)
        onNodeWithText("console-00000", substring = true).assertIsDisplayed()
        val before = runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        onNodeWithText("console-00000", substring = true).performMouseInput {
            moveTo(Offset(20f, 20f))
            scroll(3f)
        }
        waitForIdle()
        runOnIdle {
            val hasAdvanced = state.firstVisibleItemIndex > before.first ||
                (state.firstVisibleItemIndex == before.first && state.firstVisibleItemScrollOffset > before.second)
            assertTrue(hasAdvanced, "The tool payload must route vertical wheel input into the outer chat")
        }
    }

    @Test
    fun `disclosure survives disposal streamed replacement and history prepend`() = runSkikoComposeUiTest(
        size = Size(640f, 440f),
    ) {
        val section = HbChatSection("session", "Current session")
        val toolMessage = inlineToolMessage(outputLines = 200)
        var timeline by mutableStateOf(
            HbChatTimeline.from(section, (plainMessages("before", 40) + toolMessage).toImmutableList()),
        )
        val state = LazyListState(firstVisibleItemIndex = 39)
        val expansion = HbToolExpansionState()
        setContent { InlineToolHost(timeline, state, toolExpansionState = expansion) }
        onNodeWithText("workspace.inspect").performClick()
        onNodeWithTag("focus-sink").performSemanticsAction(SemanticsActions.RequestFocus)
        onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        onNodeWithText("workspace.inspect").assertDoesNotExist()
        val anchor = runOnIdle { readingAnchor(state) }
        runOnIdle {
            timeline = timeline.replaceLatest(streamedToolMessage(toolMessage))
            timeline = timeline.prepend(
                HbChatSection("older", "Earlier session"),
                plainMessages("older", 50).toImmutableList(),
            )
        }
        runOnIdle {
            assertTrue(expansion.isExpanded("tool-message", "inspect"))
            assertEquals(anchor, readingAnchor(state), "An offscreen tool update must not move the reader")
        }
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("workspace.inspect"))
        onNodeWithText("workspace.inspect").assertIsDisplayed()
        onAllNodes(hasScrollToIndexAction()).assertCountEquals(1)
    }

    @Test
    fun `restored position inside expanded output stays put on mount and streamed tail replacement`() =
        runSkikoComposeUiTest(size = Size(640f, 440f)) {
            val section = HbChatSection("restored", "Restored session")
            val toolMessage = inlineToolMessage(outputLines = 1200)
            var timeline by mutableStateOf(
                HbChatTimeline.from(section, (plainMessages("before", 40) + toolMessage).toImmutableList()),
            )
            val state = LazyListState(firstVisibleItemIndex = 70, firstVisibleItemScrollOffset = 12)
            val expansion = HbToolExpansionState().apply { setExpanded("tool-message", "inspect", true) }
            assertTrue(70 > timeline.itemCount, "The restored index must be past the collapsed timeline's end")
            setContent { InlineToolHost(timeline, state, toolExpansionState = expansion) }
            val anchor = runOnIdle {
                assertEquals(
                    70,
                    state.firstVisibleItemIndex,
                    "Mounting must not treat an expanded row as the latest item",
                )
                assertEquals(12, state.firstVisibleItemScrollOffset)
                readingAnchor(state)
            }
            runOnIdle { timeline = timeline.replaceLatest(streamedToolMessage(toolMessage)) }
            runOnIdle {
                assertEquals(70, state.firstVisibleItemIndex)
                assertEquals(anchor, readingAnchor(state), "Streaming must preserve the restored expanded-row anchor")
            }
        }

    @Test
    fun `collapsing output above the viewport keeps the same visible message and offset`() = runSkikoComposeUiTest(
        size = Size(640f, 440f),
    ) {
        val timeline = toolTimeline(outputLines = 12000)
        val state = LazyListState(firstVisibleItemIndex = 1)
        val expansion = HbToolExpansionState().apply { setExpanded("tool-message", "inspect", true) }
        setContent { InlineToolHost(timeline, state, toolExpansionState = expansion) }
        val readerIndex = runOnIdle { state.layoutInfo.totalItemsCount - 20 }
        onNode(hasScrollToIndexAction()).performScrollToIndex(readerIndex)
        onNodeWithText("after message 20", substring = true).assertIsDisplayed()
        val anchor = runOnIdle { readingAnchor(state) }
        runOnIdle { expansion.setExpanded("tool-message", "inspect", false) }
        onNodeWithText("after message 20", substring = true).assertIsDisplayed()
        runOnIdle {
            assertFalse(expansion.isExpanded("tool-message", "inspect"))
            assertEquals(anchor, readingAnchor(state), "Collapsing earlier output must preserve the reading anchor")
        }
        onAllNodes(hasScrollToIndexAction()).assertCountEquals(1)
    }
}

@Composable
private fun InlineToolHost(
    timeline: HbChatTimeline,
    state: LazyListState,
    toolExpansionState: HbToolExpansionState = rememberHbToolExpansionState(),
    onAppearance: () -> Unit = {},
) {
    HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
        Box(modifier = Modifier.fillMaxSize().background(HbTheme.colors.background).testTag("focus-sink").focusable()) {
            HbChatTranscript(
                timeline = timeline,
                state = state,
                toolExpansionState = toolExpansionState,
                messageAppearance = { message ->
                    onAppearance()
                    message.appearance
                },
            )
        }
    }
}

private fun toolTimeline(outputLines: Int): HbChatTimeline = HbChatTimeline.from(
    HbChatSection("session", "Current session"),
    (listOf(inlineToolMessage(outputLines)) + plainMessages("after", 40)).toImmutableList(),
)

private fun inlineToolMessage(outputLines: Int): HbChatMessage = HbChatMessage(
    id = "tool-message",
    author = "Agent",
    text = "",
    toolCalls = persistentListOf(
        HbToolCall(
            id = "inspect",
            title = "workspace.inspect",
            blocks = persistentListOf(
                HbToolBlock.Console(
                    id = "console",
                    text = (0 until outputLines).joinToString("\n") {
                        "console-${it.toString().padStart(5, '0')} output"
                    },
                ),
                HbToolBlock.Diff(
                    id = "diff",
                    text = (0 until outputLines).joinToString("\n") {
                        "+diff-${it.toString().padStart(5, '0')} change"
                    },
                ),
            ),
        ),
    ),
)

private fun plainMessages(prefix: String, count: Int): List<HbChatMessage> = (0 until count).map { index ->
    HbChatMessage("$prefix-$index", "Agent", "$prefix message $index\nA second line\nA third line")
}

private fun streamedToolMessage(message: HbChatMessage): HbChatMessage {
    val tool = message.toolCalls.single()
    val updatedBlocks = tool.blocks.map { block ->
        if (block is HbToolBlock.Console) block.copy(text = block.text + "\nstreamed output") else block
    }.toImmutableList()
    return message.copy(
        status = HbMessageStatus.Streaming,
        toolCalls = persistentListOf(
            tool.copy(status = HbToolStatus.Running, summary = "Still streaming", blocks = updatedBlocks),
        ),
    )
}

private fun readingAnchor(state: LazyListState): Pair<Any, Int> =
    state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key to
        state.firstVisibleItemScrollOffset
