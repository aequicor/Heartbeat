package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.collapsed
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Instant

class StudioFeedCollapseTest {
    private val reply = MessageUi.Reply(
        id = "reply",
        createdAt = Instant.DISTANT_PAST,
        text = "",
        tools = persistentListOf(tool("t1"), tool("t2")),
        isStreaming = false,
        parts = persistentListOf(
            ReplyPartUi.Reasoning("r", "thinking"),
            ReplyPartUi.Text("a", "Looking"),
            ReplyPartUi.Tool(tool("t1")),
            ReplyPartUi.Tool(tool("t2", ToolStatusUi.Running)),
            ReplyPartUi.Text("b", "Done"),
        ),
    )

    @Test
    fun `nothing changes while the feed is not collapsed`() {
        val feed = persistentListOf<MessageUi>(reply)
        assertSame(feed, feed.collapsed(FeedCollapseUi()))
    }

    @Test
    fun `reasoning disappears and the tool calls become one line at the first call`() {
        val collapsed = persistentListOf<MessageUi>(reply)
            .collapsed(FeedCollapseUi(isReasoningCollapsed = true, isToolCallsCollapsed = true))
            .single() as MessageUi.Reply

        assertEquals(
            listOf(
                ReplyPartUi.Text("a", "Looking"),
                ReplyPartUi.CollapsedTools("collapsed:reply", 2, isRunning = true),
                ReplyPartUi.Text("b", "Done"),
            ),
            collapsed.parts,
        )
        assertEquals(emptyList(), collapsed.tools)
    }

    @Test
    fun `an unchanged reply keeps its collapsed instance across emissions`() {
        val prompt = MessageUi.Prompt("prompt", Instant.DISTANT_PAST, "Go")
        val first = persistentListOf(prompt, reply).collapsed(both)
        val second = persistentListOf(prompt, reply).collapsed(both)

        assertSame(first[0], second[0])
        assertSame(first[1], second[1])
        assertNotSame(reply, first[1])
    }

    @Test
    fun `a feed with nothing to hide is returned as is`() {
        val plain = MessageUi.Reply(
            id = "plain",
            createdAt = Instant.DISTANT_PAST,
            text = "Hi",
            tools = persistentListOf(),
            isStreaming = false,
            parts = persistentListOf(ReplyPartUi.Text("a", "Hi")),
        )
        val feed = persistentListOf<MessageUi>(MessageUi.Prompt("prompt", Instant.DISTANT_PAST, "Go"), plain)

        assertSame(feed, feed.collapsed(both))
    }

    @Test
    fun `failed and cancelled calls stay visible next to the collapsed line`() {
        val failed = tool("t3", ToolStatusUi.Failed)
        val cancelled = tool("t4", ToolStatusUi.Cancelled)
        val withFailures = reply.copy(
            tools = persistentListOf(tool("t1"), failed, cancelled),
            parts = persistentListOf(
                ReplyPartUi.Tool(tool("t1")),
                ReplyPartUi.Tool(failed),
                ReplyPartUi.Tool(cancelled),
            ),
        )
        val collapsed = persistentListOf<MessageUi>(withFailures).collapsed(both).single() as MessageUi.Reply

        assertEquals(
            listOf(
                ReplyPartUi.CollapsedTools("collapsed:reply", 1, isRunning = false),
                ReplyPartUi.Tool(failed),
                ReplyPartUi.Tool(cancelled),
            ),
            collapsed.parts,
        )
        assertEquals(listOf(failed, cancelled), collapsed.tools)
    }

    @Test
    fun `a streaming reply ending in hidden reasoning shows its collapsed line running`() {
        val streaming = reply.copy(
            isStreaming = true,
            parts = persistentListOf(
                ReplyPartUi.Text("a", "Looking"),
                ReplyPartUi.Tool(tool("t1")),
                ReplyPartUi.Reasoning("r", "thinking"),
            ),
        )
        val collapsed = persistentListOf<MessageUi>(streaming).collapsed(both).single() as MessageUi.Reply

        assertEquals(ReplyPartUi.CollapsedTools("collapsed:reply", 1, isRunning = true), collapsed.parts.last())
    }

    private val both = FeedCollapseUi(isReasoningCollapsed = true, isToolCallsCollapsed = true)

    private fun tool(id: String, status: ToolStatusUi = ToolStatusUi.Done) =
        ToolUi(id = id, title = id, status = status, output = "", diff = null)
}
