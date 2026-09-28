package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HbMarkdownParserTest {
    @Test
    fun `hard breaks render once while soft line endings remain in the same paragraph`() {
        for (breakMarker in listOf("  ", "\\")) {
            val block = parseHbMarkdown("First${breakMarker}\nSecond\ncontinued").single()
            assertEquals("First\nSecond continued", block.content.text)
        }
    }

    @Test
    fun `long prose continues at word boundaries with stable segment flags and intact formatting`() {
        val text = "A readable paragraph with words and spaces. ".repeat(160).trimEnd()
        val blocks = parseHbMarkdown("**$text**")
        assertTrue(blocks.size > 2)
        assertEquals(text, blocks.joinToString("") { it.content.text })
        assertEquals(1, blocks.count { it.isFirstSegment })
        assertEquals(1, blocks.count { it.isLastSegment })
        assertTrue(blocks.first().isFirstSegment)
        assertTrue(blocks.last().isLastSegment)
        blocks.dropLast(1).forEach { assertTrue(it.content.text.last().isWhitespace()) }
        blocks.forEach { assertEquals(it.content.text, it.content.spanText(HbMarkdownStyle.Bold)) }
    }

    @Test
    fun `headings and nested inline styles produce semantic text rather than delimiter glyphs`() {
        val blocks = parseHbMarkdown(
            "# A **clear** title\n\nA **bold and *italic*** answer with `code` and ~~removed~~.",
        )
        val heading = blocks.first()
        assertEquals(HbMarkdownBlockKind.Heading, heading.kind)
        assertEquals("A clear title", heading.content.text)
        assertEquals("clear", heading.content.spanText(HbMarkdownStyle.Bold))
        val paragraph = blocks.last().content
        assertEquals("A bold and italic answer with code and removed.", paragraph.text)
        assertEquals("bold and italic", paragraph.spanText(HbMarkdownStyle.Bold))
        assertEquals("italic", paragraph.spanText(HbMarkdownStyle.Italic))
        assertEquals("code", paragraph.spanText(HbMarkdownStyle.Code))
        assertEquals("removed", paragraph.spanText(HbMarkdownStyle.Strike))
    }

    @Test
    fun `an unfinished streamed fence remains literal code and keeps its stable id when closed`() {
        val prefix = "A plan.\n\n```kotlin\nval result = \"**literal**\""
        val streaming = parseHbMarkdown(prefix)
        val completed = parseHbMarkdown("$prefix\n```")
        assertEquals(HbMarkdownBlockKind.Code, streaming.last().kind)
        assertEquals("kotlin", streaming.last().language)
        assertEquals("val result = \"**literal**\"", streaming.last().content.text)
        assertEquals(streaming.map { it.id }, completed.map { it.id })
        assertEquals(streaming, completed)
    }

    @Test
    fun `lists quotes and table cells remain separate lazy rows`() {
        val blocks = parseHbMarkdown(
            "1. First\n   - Nested\n\n> A *quote*\n\n| Name | Result |\n| --- | --- |\n| parser | **ready** |",
        )
        assertEquals("1.", blocks[0].marker)
        assertEquals("•", blocks[1].marker)
        assertEquals(1, blocks[1].level)
        assertEquals(HbMarkdownBlockKind.Quote, blocks[2].kind)
        val table = blocks.filter { it.kind == HbMarkdownBlockKind.TableRow }
        assertEquals(2, table.size)
        assertTrue(table[0].isTableHeader)
        assertEquals(listOf("Name", "Result"), table[0].cells.map { it.text })
        assertEquals("ready", table[1].cells[1].spanText(HbMarkdownStyle.Bold))
    }

    @Test
    fun `task list markers preserve checked state and formatted task text`() {
        val blocks = parseHbMarkdown(
            "- [x] Finished **task**\n- [ ] Pending task\n- [X] Also finished\n- Ordinary bullet",
        )
        assertEquals(listOf("☑", "☐", "☑", "•"), blocks.map { it.marker })
        assertEquals(
            listOf("Finished task", "Pending task", "Also finished", "Ordinary bullet"),
            blocks.map { it.content.text },
        )
        assertEquals("task", blocks.first().content.spanText(HbMarkdownStyle.Bold))
        val pending = parseHbMarkdown("- [ ] Task").single()
        val complete = parseHbMarkdown("- [x] Task").single()
        assertEquals(pending.id, complete.id)
        assertEquals(pending.content, complete.content)
    }

    @Test
    fun `images stay inert and unsafe destinations never create interactive links`() {
        val block = parseHbMarkdown(
            "[Example](https://example.com) [blocked](javascript:alert) " +
                "![Image description](https://example.com/image.png)",
        ).single()
        assertEquals("Example blocked Image description", block.content.text)
        val links = block.content.spans.filter { it.style == HbMarkdownStyle.Link }
        assertEquals(listOf("https://example.com"), links.map { it.destination })
        assertEquals(null, safeMarkdownDestination("data:text/html,hello"))
    }

    @Test
    fun `large paragraphs and code are bounded without losing text or splitting emoji`() {
        val text = "a".repeat(MARKDOWN_CHUNK_CHARACTERS - 1) + "🙂" + " tail".repeat(1800)
        val chunks = chunkHbText(
            HbMarkdownText(text, persistentListOf(HbMarkdownSpan(0, text.length, HbMarkdownStyle.Bold))),
        )
        assertTrue(chunks.size > 2)
        assertEquals(text, chunks.joinToString("") { it.text })
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= MARKDOWN_CHUNK_CHARACTERS)
            assertFalse(chunk.text.first().isLowSurrogate())
            assertFalse(chunk.text.last().isHighSurrogate())
            assertEquals(chunk.text, chunk.spanText(HbMarkdownStyle.Bold))
        }
        val code = (1..200).joinToString("\n") { "line $it" }
        val blocks = parseHbMarkdown("```\n$code\n```")
        assertTrue(blocks.size >= 8)
        assertEquals(code, blocks.joinToString("") { it.content.text })
        assertEquals(blocks.size, blocks.map { it.id }.toSet().size)
    }
}

private fun HbMarkdownText.spanText(style: HbMarkdownStyle): String {
    val span = spans.single { it.style == style }
    return text.substring(span.start, span.end)
}
