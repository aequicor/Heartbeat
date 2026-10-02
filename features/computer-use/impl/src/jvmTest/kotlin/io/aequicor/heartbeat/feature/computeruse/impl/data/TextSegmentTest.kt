package io.aequicor.heartbeat.feature.computeruse.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextSegmentTest {
    @Test
    fun `plain text stays one literal run`() {
        assertEquals(listOf(TextSegment.Literal("привет hello")), textSegments("привет hello"))
    }

    @Test
    fun `newline and tab become their own segments`() {
        assertEquals(
            listOf(
                TextSegment.Literal("a"),
                TextSegment.Newline,
                TextSegment.Tab,
                TextSegment.Literal("b"),
            ),
            textSegments("a\n\tb"),
        )
    }

    @Test
    fun `leading and trailing control characters produce no empty literals`() {
        assertEquals(
            listOf(TextSegment.Newline, TextSegment.Literal("x"), TextSegment.Newline),
            textSegments("\nx\n"),
        )
    }

    @Test
    fun `consecutive control characters are kept one by one`() {
        val segments = textSegments("\n\n\n")
        assertTrue(segments.all { it is TextSegment.Newline })
        assertEquals(3, segments.size)
    }

    @Test
    fun `empty text has no segments`() {
        assertEquals(emptyList(), textSegments(""))
    }
}
