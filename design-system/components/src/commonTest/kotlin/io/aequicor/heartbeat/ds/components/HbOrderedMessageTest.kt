package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HbOrderedMessageTest {
    private val thought = HbToolCall(
        "thought",
        "Reasoning",
        blocks = persistentListOf(HbToolBlock.Markdown("reason", "Compare the API")),
        kind = HbToolKind.Reasoning,
    )
    private val tool = HbToolCall("read", "Read files", blocks = persistentListOf(HbToolBlock.Console("out", "source")))

    @Test
    fun `ordered answer keeps tools and reasoning between prose with one pair of outer edges`() {
        val message = HbChatMessage(
            "reply",
            "Heartbeat",
            "Legacy fallback is not repeated",
            parts = persistentListOf(
                HbMessagePart.Text("intro", "First"),
                HbMessagePart.Tool(thought),
                HbMessagePart.Text("progress", "Next"),
                HbMessagePart.Tool(tool),
                HbMessagePart.Text("final", "Last"),
            ),
        )
        val chunks = transcriptChunks(message)
        assertEquals(
            listOf("First", "thought", "Next", "read", "Last"),
            chunks.map { chunk ->
                when (val body = chunk.body) {
                    is HbTranscriptBody.Markdown -> body.block.content.text
                    is HbTranscriptBody.Tool -> body.call.id
                    else -> error("Unexpected ordered body")
                }
            },
        )
        assertEquals(1, chunks.count { it.isFirst })
        assertEquals(1, chunks.count { it.isLast })
        assertTrue(chunks.first().isFirst)
        assertTrue(chunks.last().isLast)
    }

    @Test
    fun `tail prose streaming retains earlier tool keys and prepared payload references`() {
        val parts = persistentListOf(
            HbMessagePart.Text("intro", "Start"),
            HbMessagePart.Tool(tool),
            HbMessagePart.Text("tail", "One"),
        )
        val message = HbChatMessage("reply", "Heartbeat", "", parts = parts)
        val original = HbChatTimeline.from(HbChatSection("today", "Today"), persistentListOf(message))
        val updated = original.replaceLatest(
            message.copy(parts = parts.set(2, HbMessagePart.Text("tail", "One two three"))),
        )
        val before = original.sections.single().entries.first { it.body is HbTranscriptBody.Tool }
        val after = updated.sections.single().entries.first { it.body is HbTranscriptBody.Tool }
        assertEquals(before.key, after.key)
        assertSame((before.body as HbTranscriptBody.Tool).rows, (after.body as HbTranscriptBody.Tool).rows)
        assertSame(original.sections.single().entries.first(), updated.sections.single().entries.first())
    }
}
