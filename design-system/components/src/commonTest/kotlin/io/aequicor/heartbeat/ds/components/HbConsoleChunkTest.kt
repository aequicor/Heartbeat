package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HbConsoleChunkTest {
    @Test
    fun `a long classified line retains its tone through bounded continuations`() {
        val source = "ERROR: " + "details🙂 ".repeat(1000)
        val chunks = chunkHbConsole(source)
        assertTrue(chunks.size > 3)
        assertEquals(source, chunks.joinToString("") { it.text })
        assertEquals(1, chunks.count { it.isFirst })
        assertEquals(1, chunks.count { it.isLast })
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= MARKDOWN_CHUNK_CHARACTERS)
            assertEquals(listOf(HbConsoleSpan(0, chunk.text.length, HbConsoleTone.Error)), chunk.spans)
            assertFalse(chunk.text.first().isLowSurrogate())
            assertFalse(chunk.text.last().isHighSurrogate())
        }
    }

    @Test
    fun `boundary newlines do not add blank display rows or remove intentional blank lines`() {
        val source = "INFO ready\r\n".repeat(23) + "\r\n\r\nplain final\r\n"
        val chunks = chunkHbConsole(source)
        assertEquals(source, chunks.joinToString("") { it.text })
        assertEquals(source, chunks.joinToString("\r\n") { it.displayText })
        assertTrue(chunks.first().displayText.endsWith("\r\n"))
        assertTrue(chunks.last().displayText.endsWith("\r\n"))
    }

    @Test
    fun `a character boundary never splits CRLF and empty output still has one complete panel`() {
        val source = "a".repeat(MARKDOWN_CHUNK_CHARACTERS - 1) + "\r\nnext"
        val chunks = chunkHbConsole(source)
        assertEquals(source, chunks.joinToString("") { it.text })
        assertFalse(chunks.first().text.endsWith('\r'))
        assertTrue(chunks.last().text.startsWith("\r\n"))
        val empty = chunkHbConsole("").single()
        assertTrue(empty.isFirst && empty.isLast)
        assertTrue(empty.spans.isEmpty())
        assertEquals("", empty.displayText)
    }
}
