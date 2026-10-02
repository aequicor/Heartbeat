package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HbChatTimelineTest {
    @Test
    fun `streaming tail shares previous rows and preserves their keys`() {
        val section = HbChatSection("session", "Session")
        val original = HbChatTimeline.Empty
            .append(section, HbChatMessage("first", "Agent", "History"))
            .append(section, HbChatMessage("stream", "Agent", "Start", status = HbMessageStatus.Streaming))
        val updated = original.replaceLatest(original.latestMessage!!.copy(text = "Start with another token"))

        assertSame(original.sections.first().entries.first(), updated.sections.first().entries.first())
        assertSame(original.messages.first(), updated.messages.first())
        assertEquals(original.sections.first().entries.last().key, updated.sections.first().entries.last().key)
        assertEquals(2, updated.messageCount)
        assertEquals("Start with another token", updated.latestMessage!!.text)
    }

    @Test
    fun `large messages flatten into bounded rows without dropping text`() {
        val text = "A long line with repeated content. ".repeat(800)
        val timeline = HbChatTimeline.Empty.append(
            HbChatSection("today", "Today"),
            HbChatMessage("large", "Agent", text),
        )
        val rows = timeline.sections.first().entries
        assertTrue(rows.size > 10)
        assertTrue(rows.all { (it.body as HbTranscriptBody.Text).text.length <= 2048 })
        assertEquals(text, rows.joinToString("") { (it.body as HbTranscriptBody.Text).text })
        assertEquals(1, rows.count { it.isFirst })
        assertEquals(1, rows.count { it.isLast })
        assertEquals(rows.size + 1, timeline.itemCount)
    }

    @Test
    fun `prepending same section retains one header and keeps latest replacement valid`() {
        val section = HbChatSection("session", "Session")
        val tail = HbChatMessage("tail", "Agent", "Latest")
        val timeline = HbChatTimeline.Empty.append(section, tail)
            .prepend(section, persistentListOf(HbChatMessage("older", "Agent", "History")))
            .replaceLatest(tail.copy(text = "Latest update"))

        assertEquals(1, timeline.sections.size)
        assertEquals(listOf("older", "tail"), timeline.messages.map { it.id })
        assertEquals(3, timeline.itemCount)
        assertEquals("History", (timeline.sections.first().entries.first().body as HbTranscriptBody.Text).text)
    }

    @Test
    fun `new history section adds one header and rejects duplicate message ids at ingestion`() {
        val timeline = HbChatTimeline.Empty.append(
            HbChatSection("today", "Today"),
            HbChatMessage("new", "Agent", "Now"),
        )
            .prepend(HbChatSection("yesterday", "Yesterday"), persistentListOf(HbChatMessage("old", "Agent", "Before")))
        assertEquals(listOf("yesterday", "today"), timeline.sections.map { it.section.id })
        assertEquals(4, timeline.itemCount)
        assertFailsWith<IllegalArgumentException> {
            timeline.append(HbChatSection("today", "Today"), HbChatMessage("old", "Agent", "Duplicate"))
        }
    }

    @Test
    fun `streaming text moves tool index while retaining prepared payload and historical rows`() {
        val section = HbChatSection("today", "Today")
        val history = toolMessage("history")
        val tail = toolMessage("tail").copy(status = HbMessageStatus.Streaming)
        val original = HbChatTimeline.from(section, persistentListOf(history, tail))
        val originalSection = original.sections.single()
        val originalTool = originalSection.entries.last()
        val updated = original.replaceLatest(tail.copy(text = "token ".repeat(600)))
        val updatedSection = updated.sections.single()
        val updatedTool = updatedSection.entries.last()

        assertEquals(originalTool.key, updatedTool.key)
        val previousIndex = originalSection.toolEntries.getValue(originalTool.key)
        assertTrue(updatedSection.toolEntries.getValue(updatedTool.key) > previousIndex)
        assertSame((originalTool.body as HbTranscriptBody.Tool).rows, (updatedTool.body as HbTranscriptBody.Tool).rows)
        assertSame(originalSection.entries.first(), updatedSection.entries.first())
        val historyTool = originalSection.entries.first { it.body is HbTranscriptBody.Tool }
        assertEquals(originalSection.toolEntries[historyTool.key], updatedSection.toolEntries[historyTool.key])
        assertEquals(2, updatedSection.toolEntries.size)
    }

    @Test
    fun `tool metadata update reuses payload but changed blocks replace prepared rows`() {
        val section = HbChatSection("today", "Today")
        val message = toolMessage("tail")
        val original = HbChatTimeline.from(section, persistentListOf(message))
        val initialBody = original.sections.single().entries.last().body as HbTranscriptBody.Tool
        val updatedCall = message.toolCalls.single().copy(status = HbToolStatus.Error, title = "Inspection failed")
        val metadataUpdate = original.replaceLatest(message.copy(toolCalls = persistentListOf(updatedCall)))
        val metadataBody = metadataUpdate.sections.single().entries.last().body as HbTranscriptBody.Tool
        val changedCall = updatedCall.copy(blocks = persistentListOf(HbToolBlock.Console("output", "New output")))
        val payloadUpdate = metadataUpdate.replaceLatest(message.copy(toolCalls = persistentListOf(changedCall)))
        val changedBody = payloadUpdate.sections.single().entries.last().body as HbTranscriptBody.Tool

        assertSame(initialBody.rows, metadataBody.rows)
        assertEquals(HbToolStatus.Error, metadataBody.call.status)
        assertNotSame(metadataBody.rows, changedBody.rows)
        assertTrue(changedBody.rows.any { it.text == "New output" })
        val removed = payloadUpdate.replaceLatest(message.copy(toolCalls = persistentListOf()))
        assertTrue(removed.sections.single().toolEntries.isEmpty())
    }

    @Test
    fun `same section prepend shifts only tool positions and retains payload references`() {
        val section = HbChatSection("today", "Today")
        val tail = toolMessage("tail")
        val original = HbChatTimeline.from(section, persistentListOf(tail))
        val originalTool = original.sections.single().entries.last()
        val prepended = original.prepend(section, persistentListOf(toolMessage("older")))
        val entries = prepended.sections.single().entries
        val tools = prepended.sections.single().toolEntries

        assertEquals(2, tools.size)
        tools.forEach { (key, index) -> assertEquals(key, entries[index].key) }
        assertSame(originalTool, entries[tools.getValue(originalTool.key)])
        val replaced = prepended.replaceLatest(tail.copy(text = "Updated response"))
        assertEquals(tools, replaced.sections.single().toolEntries)
        assertSame(
            (originalTool.body as HbTranscriptBody.Tool).rows,
            (replaced.sections.single().entries.last().body as HbTranscriptBody.Tool).rows,
        )
    }

    @Test
    fun `prepending another section shares original tool index and permits repeated call ids across messages`() {
        val today = HbChatSection("today", "Today")
        val original = HbChatTimeline.from(today, persistentListOf(toolMessage("first"), toolMessage("second")))
        val originalSection = original.sections.single()
        val prepended = original.prepend(
            HbChatSection("yesterday", "Yesterday"),
            persistentListOf(toolMessage("older")),
        )

        assertEquals(2, originalSection.toolEntries.size)
        assertSame(originalSection, prepended.sections.last())
        assertSame(originalSection.toolEntries, prepended.sections.last().toolEntries)
        assertTrue(originalSection.toolEntries.keys.all { it.startsWith("message:") })
    }

    @Test
    fun `host tail entries reuse their prepared chunks while the history before them streams`() {
        val section = HbChatSection("session", "Session")
        val stream = HbChatMessage("stream", "Agent", "Start", status = HbMessageStatus.Streaming)
        val card = toolMessage("card").copy(role = HbChatRole.System)
        val history = HbChatTimeline.Empty.append(section, stream)
        val first = history.appendTail(section, listOf(card))
        assertEquals(listOf("stream", "card"), first.messages.map { it.id })

        val streamed = history.replaceLatest(stream.copy(text = "Start with another token"))
            .appendTail(section, listOf(card), previous = first)
        assertEquals(listOf("stream", "card"), streamed.messages.map { it.id })
        assertSame(first.sections.single().entries.last(), streamed.sections.single().entries.last())

        val call = card.toolCalls.single()
        val changed = card.copy(toolCalls = persistentListOf(call.copy(status = HbToolStatus.Error)))
        val updated = history.appendTail(section, listOf(changed), previous = streamed)
        val before = streamed.sections.single().entries.last { it.body is HbTranscriptBody.Tool }.body
        val after = updated.sections.single().entries.last { it.body is HbTranscriptBody.Tool }.body
        assertEquals(HbToolStatus.Error, (after as HbTranscriptBody.Tool).call.status)
        assertSame((before as HbTranscriptBody.Tool).rows, after.rows)
        assertSame(history, history.appendTail(section, emptyList(), previous = updated))
    }

    @Test
    fun `replacing a woven card keeps its position and shares prepared rows`() {
        val section = HbChatSection("session", "Session")
        val card = toolMessage("card").copy(role = HbChatRole.System)
        val timeline = HbChatTimeline.Empty
            .append(section, HbChatMessage("first", "Agent", "History"))
            .append(section, card)
        val changed = card.copy(toolCalls = persistentListOf(card.toolCalls.single().copy(status = HbToolStatus.Error)))
        val updated = timeline.replace("card", changed)

        assertEquals(listOf("first", "card"), updated.messages.map { it.id })
        assertEquals(HbToolStatus.Error, updated.messages.last().toolCalls.single().status)
        assertSame(timeline.messages.first(), updated.messages.first())
        assertSame(
            (timeline.sections.single().entries.first().body as HbTranscriptBody.Text),
            updated.sections.single().entries.first().body,
        )
        assertSame(
            (timeline.sections.single().entries.last().body as HbTranscriptBody.Tool).rows,
            (updated.sections.single().entries.last().body as HbTranscriptBody.Tool).rows,
        )
        assertEquals(timeline.itemCount, updated.itemCount)
        assertEquals(timeline.sections.single().toolEntries, updated.sections.single().toolEntries)
        assertFailsWith<IllegalArgumentException> { updated.replace("missing", changed) }
    }

    @Test
    fun `removing a woven card keeps the history and the streaming tail replaceable`() {
        val section = HbChatSection("session", "Session")
        val card = toolMessage("card").copy(role = HbChatRole.System)
        val stream = HbChatMessage("stream", "Agent", "Start", status = HbMessageStatus.Streaming)
        val timeline = HbChatTimeline.Empty
            .append(section, HbChatMessage("first", "Agent", "History"))
            .append(section, card)
            .append(section, stream)
        val removed = timeline.remove("card")

        assertEquals(listOf("first", "stream"), removed.messages.map { it.id })
        assertEquals(timeline.itemCount - 2, removed.itemCount)
        val streamed = removed.replaceLatest(stream.copy(text = "Start with another token"))
        assertEquals("Start with another token", streamed.latestMessage!!.text)
        assertEquals(listOf("first", "stream"), streamed.messages.map { it.id })
        assertFailsWith<IllegalArgumentException> { removed.remove("card") }
    }

    @Test
    fun `replacing a mid-timeline message with other chunk count re-indexes tools and keeps streaming`() {
        val section = HbChatSection("today", "Today")
        val stream = HbChatMessage("stream", "Agent", "Start", status = HbMessageStatus.Streaming)
        val original = HbChatTimeline.from(
            section,
            persistentListOf(toolMessage("history"), HbChatMessage("text", "Agent", "Plain"), stream),
        )
        val grown = original.replace(
            "text",
            HbChatMessage("text", "Agent", "First\n\nSecond", kind = HbMessageKind.Markdown),
        )

        assertEquals(original.itemCount + 1, grown.itemCount)
        val grownEntries = grown.sections.single().entries
        // The tool history keeps its index; the grown message contributes two markdown chunks before the stream.
        assertEquals(5, grownEntries.size)
        assertTrue(grownEntries[2].body is HbTranscriptBody.Markdown)
        assertEquals("Start", (grownEntries.last().body as HbTranscriptBody.Text).text.take(5))
        grown.sections.single().toolEntries.forEach { (key, index) -> assertEquals(key, grownEntries[index].key) }

        val streamed = grown.replaceLatest(stream.copy(text = "Start with another token"))
        assertEquals("Start with another token", streamed.latestMessage!!.text)
        assertEquals(listOf("history", "text", "stream"), streamed.messages.map { it.id })
        streamed.sections.single().toolEntries.forEach { (key, index) ->
            assertEquals(key, streamed.sections.single().entries[index].key)
        }
    }

    private fun toolMessage(id: String): HbChatMessage = HbChatMessage(
        id,
        "Agent",
        "Response",
        toolCalls = persistentListOf(
            HbToolCall(
                id = "inspect",
                title = "Inspect workspace",
                blocks = persistentListOf(HbToolBlock.Console("output", "Original output\n".repeat(80))),
            ),
        ),
    )
}
