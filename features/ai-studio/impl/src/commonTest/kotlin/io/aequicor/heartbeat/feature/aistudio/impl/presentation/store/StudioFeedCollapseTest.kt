package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFeedCollapse
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertSame(feed, feed.collapsed(StudioFeedCollapse()))
    }

    @Test
    fun `reasoning disappears and the tool calls become one line at the first call`() {
        val collapsed = persistentListOf<MessageUi>(reply)
            .collapsed(StudioFeedCollapse(isReasoningCollapsed = true, isToolCallsCollapsed = true))
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

    private fun tool(id: String, status: ToolStatusUi = ToolStatusUi.Done) =
        ToolUi(id = id, title = id, status = status, output = "", diff = null)
}
