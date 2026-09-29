package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoogStreamPartsTest {
    @Test
    fun `deltas accumulate per block and a complete frame replaces the block text`() {
        val parts = KoogStreamParts()

        assertTrue(parts.append(StreamFrame.TextDelta("Hel", index = 0)))
        assertTrue(parts.append(StreamFrame.TextDelta("lo", index = 0)))
        assertTrue(parts.append(StreamFrame.TextDelta(" world", index = 1)))
        assertEquals("Hello world", parts.text)

        assertTrue(parts.append(StreamFrame.TextComplete("Hi", index = 0)))
        assertEquals(listOf(ContentPart.Text("Hi world")), parts.parts)
        assertFalse(parts.append(StreamFrame.TextComplete("Hi", index = 0)), "an unchanged block is not an update")
    }

    @Test
    fun `reasoning prefers its summary and keeps it when the complete frame has none`() {
        val parts = KoogStreamParts()

        parts.append(StreamFrame.ReasoningDelta(text = "think", summary = null, index = 0))
        assertEquals(listOf(ContentPart.Reasoning("think")), parts.parts)
        parts.append(StreamFrame.ReasoningDelta(text = "ing", summary = "short", index = 0))
        parts.append(StreamFrame.TextDelta("answer", index = 1))
        assertEquals(listOf(ContentPart.Reasoning("short"), ContentPart.Text("answer")), parts.parts)

        parts.append(StreamFrame.ReasoningComplete(id = "r", content = listOf("a", "b"), summary = null, index = 0))

        assertEquals(listOf(ContentPart.Reasoning("short"), ContentPart.Text("answer")), parts.parts)
        assertEquals("answer", parts.text)
    }

    @Test
    fun `a complete reasoning frame without any summary exposes its content`() {
        val parts = KoogStreamParts()

        parts.append(StreamFrame.ReasoningComplete(id = "r", content = listOf("a", "b"), summary = null, index = 0))

        assertEquals(listOf(ContentPart.Reasoning("a\n\nb")), parts.parts)
    }
}
