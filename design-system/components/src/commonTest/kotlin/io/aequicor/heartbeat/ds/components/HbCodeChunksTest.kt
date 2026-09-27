package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbCodeChunksTest {
    @Test
    fun `multi-line comments keep their color across lazy rows and stop at the closing delimiter`() {
        val comment = "/*\n" + "val ignored = 42 // 🙂\n".repeat(80) + "*/"
        val source = "$comment\nval active = 1"
        val blocks = parseHbMarkdown("```kotlin\n$source\n```")
        assertTrue(blocks.size > 3)
        assertEquals(source, blocks.joinToString("") { it.content.text })
        assertEquals(comment, blocks.coloredText(HbCodeTokenKind.Comment))
        assertEquals("val", blocks.coloredText(HbCodeTokenKind.Keyword))
        assertEquals("1", blocks.coloredText(HbCodeTokenKind.Number))
        blocks.forEach { block ->
            assertTrue(block.content.text.length <= MARKDOWN_CHUNK_CHARACTERS)
            block.codeSpans.orEmpty().forEach { span ->
                assertTrue(span.start >= 0 && span.end <= block.content.text.length && span.start < span.end)
            }
        }
    }

    @Test
    fun `a raw string crossing a character boundary remains a string after more text arrives`() {
        val literal = "\"\"\"" + "a".repeat(MARKDOWN_CHUNK_CHARACTERS - 8) + "🙂 val notCode".repeat(40)
        val prefix = "```kt\nval text = $literal"
        val partial = parseHbMarkdown(prefix)
        val complete = parseHbMarkdown("$prefix\"\"\"\nval count = 2\n```")
        assertEquals(literal, partial.coloredText(HbCodeTokenKind.String))
        assertEquals("$literal\"\"\"", complete.coloredText(HbCodeTokenKind.String))
        assertEquals(partial.first(), complete.first())
        assertEquals("2", complete.coloredText(HbCodeTokenKind.Number))
    }

    @Test
    fun `tool markdown uses prepared syntax while console and diff remain literal`() {
        val code = "val title = \"Ready\""
        val rows = prepareToolRows(
            persistentListOf(
                HbToolBlock.Markdown("source", "```kotlin\n$code\n```"),
                HbToolBlock.Console("console", code),
                HbToolBlock.Diff("diff", "+$code"),
            ),
        )
        val block = rows.mapNotNull { it.markdown }.single()
        assertEquals("val", listOf(block).coloredText(HbCodeTokenKind.Keyword))
        assertEquals(code, rows.single { it.section == null && it.markdown == null && !it.isDiff }.text)
        assertEquals("+$code", rows.single { it.isDiff }.text)
    }

    @Test
    fun `an unknown language keeps literal content without arbitrary token colors`() {
        val code = "// this is text: val count = 42"
        val block = parseHbMarkdown("```unknown-format\n$code\n```").single()
        assertEquals(code, block.content.text)
        assertTrue(block.codeSpans.orEmpty().isEmpty())
    }

    @Test
    fun `code messages preserve syntax context and original CRLF text through transcript preparation`() {
        val code = "/*\r\n" + "🙂 comment\r\n".repeat(70) + "*/\r\nval result = 7"
        val chunks = transcriptChunks(
            HbChatMessage("code", "Agent", code, kind = HbMessageKind.Code, codeLanguage = "kt"),
        )
        val bodies = chunks.map { it.body as HbTranscriptBody.Text }
        assertEquals(code, bodies.joinToString("") { it.text })
        assertTrue(chunks.first().isFirst && chunks.last().isLast)
        assertEquals(1, bodies.sumOf { body -> body.codeSpans.count { it.kind == HbCodeTokenKind.Keyword } })
        assertTrue(bodies.dropLast(1).all { body -> body.codeSpans.all { it.kind == HbCodeTokenKind.Comment } })
    }
}

private fun List<HbMarkdownBlock>.coloredText(kind: HbCodeTokenKind): String = joinToString("") { block ->
    block.codeSpans.orEmpty().filter { it.kind == kind }.joinToString("") { span ->
        block.content.text.substring(span.start, span.end)
    }
}
