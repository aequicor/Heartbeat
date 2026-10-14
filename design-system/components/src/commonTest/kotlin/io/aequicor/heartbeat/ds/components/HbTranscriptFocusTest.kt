package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HbTranscriptFocusTest {
    @Test
    fun `embedded target accounts for preceding headings and expanded tool rows`() {
        val answer = HbChatMessage(
            "answer",
            "Agent",
            "Done",
            toolCalls = persistentListOf(
                HbToolCall(
                    "call",
                    "Build",
                    blocks = persistentListOf(HbToolBlock.Console("output", "line\n".repeat(20))),
                ),
            ),
            hasEmbeddedContent = true,
        )
        val timeline = HbChatTimeline.Empty
            .append(HbChatSection("before", "Yesterday"), HbChatMessage("before", "User", "Hello"))
            .append(HbChatSection("today", "Today"), answer)
        val base = expandedTranscriptSections(timeline, persistentSetOf())
        val tool = base.last().source.entries.single { it.body is HbTranscriptBody.Tool }
        val expanded = expandedTranscriptSections(timeline, persistentSetOf(tool.key))
        val request = HbTranscriptFocus("answer", 1, isEmbedded = true)
        val plainIndex = base.focusIndex(request, showSectionHeaders = true)!!
        assertEquals(
            plainIndex + expanded.last().tools.single().body.rows.size,
            expanded.focusIndex(request, showSectionHeaders = true),
        )
        assertEquals(plainIndex - 2, base.focusIndex(request, showSectionHeaders = false))
        assertNull(base.focusIndex(request.copy(messageId = "missing"), true))
        assertNull(base.focusIndex(request.copy(messageId = "before"), true))
    }
}
