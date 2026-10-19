package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.SideEffect
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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class HbSectionedTranscriptUiTest {
    @Test
    fun `appending and streaming retain visible history composition`() = traceTranscript { trace ->
        runSkikoComposeUiTest(
            size = Size(480f, 900f),
        ) {
            val section = HbChatSection("session", "Session")
            val tool = HbToolCall("inspect", "Inspect", actions = persistentListOf(HbToolAction("open", "Open result")))
            val history = messages(0, 3).mapIndexed { index, message ->
                if (index == 0) message.copy(toolCalls = persistentListOf(tool)) else message
            }.toImmutableList()
            var timeline by mutableStateOf(HbChatTimeline.from(section, history))
            val compositions = mutableMapOf<String, Int>()
            val disposals = mutableSetOf<String>()
            var actionContext: String? = null
            val footer: @Composable (HbChatMessage) -> Unit = { message ->
                SideEffect { compositions[message.id] = compositions.getOrElse(message.id) { 0 } + 1 }
                DisposableEffect(message.id) {
                    onDispose { disposals.add(message.id) }
                }
            }
            setContent {
                HbTheme(darkTheme = false) {
                    val latestText = timeline.latestMessage?.text
                    HbChatTranscript(
                        timeline = timeline,
                        messageFooterContent = { message ->
                            footer(message)
                            if (message.id == "new") HbText("Footer $latestText")
                        },
                        messageEmbeddedContent = { HbText("Embedded $latestText") },
                        onToolAction = { _, _ -> actionContext = latestText },
                    )
                }
            }
            // The former latest row legitimately gains its inter-message bottom spacing on append.
            val before = runOnIdle { compositions.filterKeys { it != timeline.latestMessage!!.id } }
            runOnIdle {
                trace.rows = 0
                timeline = timeline.append(section, HbChatMessage("new", "Agent", "New message"))
            }
            runOnIdle {
                assertTrue(trace.rows <= 2, "Only new and formerly latest rows may render on append, got ${trace.rows}")
                assertTrue(disposals.isEmpty(), "Existing rows must keep their composition: $disposals")
                before.forEach { (id, count) ->
                    assertEquals(count, compositions[id], "Unchanged history $id must skip recomposition")
                }
            }
            runOnIdle {
                trace.rows = 0
                timeline = timeline.replaceLatest(timeline.latestMessage!!.copy(text = "Streamed message"))
            }
            runOnIdle {
                assertEquals(1, trace.rows, "Only the streaming row may render")
                assertTrue(disposals.isEmpty(), "Streaming must retain existing rows: $disposals")
                before.forEach { (id, count) -> assertEquals(count, compositions[id], "History $id during streaming") }
            }
            onNodeWithText("Open result").performClick()
            runOnIdle { assertEquals("Streamed message", actionContext, "Actions must use the latest host callback") }
        }
    }

    @Test
    fun `embedded content and footer receive updated host state for an unchanged message`() = runSkikoComposeUiTest {
        val timeline = HbChatTimeline.Empty.append(
            HbChatSection("session", "Session"),
            HbChatMessage("embedded", "Agent", "Message", hasEmbeddedContent = true),
        )
        var label by mutableStateOf("Initial")
        setContent {
            val hostLabel = label
            HbTheme(darkTheme = false) {
                HbChatTranscript(
                    timeline = timeline,
                    messageEmbeddedContent = { HbText("Embedded $hostLabel") },
                    messageFooterContent = { HbText("Footer $hostLabel") },
                )
            }
        }
        onNodeWithText("Embedded Initial").assertIsDisplayed()
        onNodeWithText("Footer Initial").assertIsDisplayed()
        runOnIdle { label = "Updated" }
        onNodeWithText("Embedded Updated").assertIsDisplayed()
        onNodeWithText("Footer Updated").assertIsDisplayed()
        onNodeWithText("Embedded Initial").assertDoesNotExist()
        onNodeWithText("Footer Initial").assertDoesNotExist()
    }

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

    private fun traceTranscript(test: (TranscriptTrace) -> Unit) {
        val trace = TranscriptTrace()
        Composer.setTracer(trace)
        try {
            test(trace)
        } finally {
            Composer.setTracer(null)
        }
    }

    private class TranscriptTrace : CompositionTracer {
        var rows = 0
        override fun isTraceInProgress(): Boolean = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.startsWith("io.aequicor.heartbeat.ds.components.HbTranscriptChunkContent (")) rows++
        }
        override fun traceEventEnd() = Unit
    }

    private fun messages(start: Int, count: Int) = (start until start + count).map {
        HbChatMessage("message-$it", "Agent", "Message $it\nA second line\nA third line")
    }.toImmutableList()
}
