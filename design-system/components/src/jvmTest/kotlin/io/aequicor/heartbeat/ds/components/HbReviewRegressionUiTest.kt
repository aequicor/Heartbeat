package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI regressions from the PR #6 review: follow/anchoring, controlled input and composer keys. */
@OptIn(ExperimentalTestApi::class)
class HbReviewRegressionUiTest {
    @Test
    fun `scrollbar seek pauses following so streamed tokens keep the reader in history`() =
        runSkikoComposeUiTest(size = Size(480f, 420f)) {
            var timeline by mutableStateOf(HbChatTimeline.from(HbChatSection("today", "Today"), messages(0, 200)))
            val state = LazyListState(firstVisibleItemIndex = 200)
            setContent { Transcript(timeline, state, Modifier.fillMaxSize().testTag("transcript")) }
            runOnIdle { assertFalse(state.canScrollForward, "The transcript starts at the latest message") }
            onNodeWithTag("transcript").performMouseInput {
                moveTo(Offset(width - 4f, height / 2f))
                press()
                release()
            }
            val seekIndex = runOnIdle {
                assertTrue(state.canScrollForward, "A track click must move away from the latest message")
                state.firstVisibleItemIndex
            }
            runOnIdle {
                val latest = checkNotNull(timeline.latestMessage)
                timeline = timeline.replaceLatest(latest.copy(text = latest.text + " streamed"))
            }
            runOnIdle {
                assertEquals(seekIndex, state.firstVisibleItemIndex, "Streaming must not pull the reader back")
                assertTrue(state.canScrollForward)
            }
        }

    @Test
    fun `following keeps the latest message visible when the viewport shrinks`() =
        runSkikoComposeUiTest(size = Size(480f, 420f)) {
            var height by mutableStateOf(400.dp)
            val timeline = HbChatTimeline.from(HbChatSection("today", "Today"), messages(0, 50))
            val state = LazyListState(firstVisibleItemIndex = 50)
            setContent {
                Box(Modifier.fillMaxWidth().height(height)) { Transcript(timeline, state, Modifier.fillMaxSize()) }
            }
            runOnIdle { assertFalse(state.canScrollForward) }
            runOnIdle { height = 250.dp }
            runOnIdle { assertFalse(state.canScrollForward, "Keyboard or composer growth must not hide the latest") }
        }

    @Test
    fun `a history page larger than the lazy key window keeps the reader's message`() =
        runSkikoComposeUiTest(size = Size(480f, 420f)) {
            var timeline by mutableStateOf(HbChatTimeline.from(HbChatSection("today", "Today"), messages(1000, 300)))
            val state = LazyListState(firstVisibleItemIndex = 209, firstVisibleItemScrollOffset = 5)
            setContent { Transcript(timeline, state, Modifier.fillMaxSize()) }
            val before = runOnIdle { state.firstVisibleKey() to state.firstVisibleItemScrollOffset }
            runOnIdle { timeline = timeline.prepend(HbChatSection("older", "Earlier"), messages(0, 150)) }
            runOnIdle { assertEquals(before, state.firstVisibleKey() to state.firstVisibleItemScrollOffset) }
        }

    @Test
    fun `a position restored at the start of history is not treated as following`() =
        runSkikoComposeUiTest(size = Size(480f, 420f)) {
            val timeline = HbChatTimeline.from(HbChatSection("today", "Today"), messages(0, 100))
            val state = LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
            setContent { Transcript(timeline, state, Modifier.fillMaxSize()) }
            runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
        }

    @Test
    fun `an owner answering a frame late keeps typing order and caret`() = runSkikoComposeUiTest {
        var value by mutableStateOf("")
        val reported = mutableListOf<String>()
        setContent {
            HbTheme(darkTheme = false) {
                HbTextField(value, { reported += it }, modifier = Modifier.size(280.dp, 96.dp).testTag("editor"))
            }
        }
        val editor = onNodeWithTag("editor")
        editor.performTextInput("a")
        waitForIdle()
        runOnIdle { value = reported.last() }
        editor.performTextInput("b")
        waitForIdle()
        runOnIdle { value = reported.last() }
        waitForIdle()
        assertEquals("ab", editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(listOf("a", "ab"), reported)
    }

    @Test
    fun `disabled send shortcut numpad Enter and Tab never edit the draft`() =
        runSkikoComposeUiTest(size = Size(620f, 400f)) {
            var draft by mutableStateOf("next question")
            var isStreaming by mutableStateOf(true)
            var sends = 0
            setContent {
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize()) {
                        HbChatComposer(
                            draft,
                            { draft = it },
                            { sends++ },
                            {},
                            "Send",
                            "Stop",
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "Prompt",
                            isStreaming = isStreaming,
                        )
                    }
                }
            }
            val editor = onNodeWithContentDescription("Prompt")
            editor.performSemanticsAction(SemanticsActions.RequestFocus)
            editor.performTextInputSelection(TextRange(draft.length))
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
            runOnIdle {
                assertEquals("next question", draft)
                assertEquals(0, sends)
                isStreaming = false
            }
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.NumPadEnter) } }
            runOnIdle { assertEquals(1, sends) }
            editor.performSemanticsAction(SemanticsActions.RequestFocus)
            editor.performKeyInput { pressKey(Key.Tab) }
            runOnIdle { assertEquals("next question", draft, "Tab moves focus instead of inserting a tab") }
        }

    @Test
    fun `long code fences draw no empty line at segment seams`() = runSkikoComposeUiTest(size = Size(900f, 1600f)) {
        val code = (1..60).joinToString("\n") { "val v$it = $it" }
        val block = parseHbMarkdown("```kotlin\n$code\n```").first()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme(darkTheme = false) { HbMarkdownBlockContent(block) }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        onNode(hasText("val v1 = 1", substring = true)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertEquals(block.content.text.trimEnd('\n').lines().size, layouts.single().lineCount)
    }

    private fun messages(start: Int, count: Int) = (start until start + count).map {
        HbChatMessage("message-$it", "Agent", "Message $it\nA second line\nA third line")
    }.toImmutableList()
}

@androidx.compose.runtime.Composable
private fun Transcript(timeline: HbChatTimeline, state: LazyListState, modifier: Modifier) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
            HbChatTranscript(timeline, modifier = modifier, state = state)
        }
    }
}

private fun LazyListState.firstVisibleKey(): Any =
    layoutInfo.visibleItemsInfo.first { it.index == firstVisibleItemIndex }.key
