package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HbChatPresentationTest {
    @Test
    fun `streaming replacement keeps the same identity and appearance`() {
        val original = HbChatMessage(
            id = "agent-reply",
            author = "Agent",
            text = "Hello",
            status = HbMessageStatus.Streaming,
            appearance = HbMessageAppearance(tone = HbTone.Brand, widthFraction = 1f),
        )
        val update = original.copy(text = "Hello world", status = HbMessageStatus.Complete)
        assertEquals(original.id, update.id)
        assertEquals(original.appearance, update.appearance)
    }

    @Test
    fun `caller placement overrides the user role default`() {
        val user = HbChatMessage("user", "You", "Text", role = HbChatRole.User)
        assertEquals(HbMessageAlignment.End, resolvedAlignment(user))
        assertEquals(
            HbMessageAlignment.Start,
            resolvedAlignment(user.copy(appearance = HbMessageAppearance(alignment = HbMessageAlignment.Start))),
        )
    }

    @Test
    fun `system notices center while assistant and tools stay at the start`() {
        val message = HbChatMessage("message", "System", "Text")
        assertEquals(HbMessageAlignment.Center, resolvedAlignment(message.copy(role = HbChatRole.System)))
        assertEquals(HbMessageAlignment.Start, resolvedAlignment(message.copy(role = HbChatRole.Assistant)))
        assertEquals(HbMessageAlignment.Start, resolvedAlignment(message.copy(role = HbChatRole.Tool)))
    }

    @Test
    fun `invalid dimensions fail before composition`() {
        listOf(0f, -1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { HbMessageAppearance(widthFraction = invalid) }
        }
    }

    @Test
    fun `empty and duplicate identities fail before entering the lazy list`() {
        assertFailsWith<IllegalArgumentException> { HbChatMessage(" ", "Author", "Text") }
        val message = HbChatMessage("same", "Author", "Text")
        assertFailsWith<IllegalArgumentException> {
            requireUniqueMessageIds(listOf(message, message.copy(text = "Other")))
        }
        requireUniqueMessageIds(listOf(message, message.copy(id = "different")))
        requireUniqueMessageIds(emptyList())
    }
}
