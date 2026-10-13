package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ChatWireWindowTest {
    private val at = Instant.fromEpochSeconds(1_700_000_000)

    @Test
    fun `multibyte and escaped text fits the byte budget and keeps newest entries`() {
        val text = "Я\u0000".repeat(ChatWireLimits.MAX_TEXT_CHARS / 2)
        val entries = (1..ChatWireLimits.MAX_ENTRIES).map {
            StudioChatEntry.Prompt("message-$it", at, text, 0)
        }

        val window = entries.wireWindow()
        val bytes = window.sumOf {
            BridgeProtocol.json.encodeToString(WireEntry.serializer(), it).encodeToByteArray().size + 1
        }

        assertTrue(bytes <= ChatWireLimits.MAX_TRANSCRIPT_BYTES)
        assertTrue(window.isNotEmpty() && window.size < entries.size)
        assertEquals(entries.last().id, window.last().id)
        assertEquals(entries.takeLast(window.size).map { it.id }, window.map { it.id })
    }

    @Test
    fun `one reply with many parts retains recent content and marks truncation`() {
        val parts = (1..100).map { StudioChatPart.Text("part-$it " + "Я".repeat(ChatWireLimits.MAX_TEXT_CHARS)) }
        val entry = StudioChatEntry.Reply("reply", at, parts, true)

        val wire = listOf(entry).wireWindow().single()

        assertEquals(true, wire.isTruncated)
        assertEquals(true, wire.isStreaming)
        assertTrue(wire.parts!!.isNotEmpty() && wire.parts.size < parts.size)
        assertTrue(wire.parts.last().text!!.startsWith("part-100 "))
        val bytes = BridgeProtocol.json.encodeToString(WireEntry.serializer(), wire).encodeToByteArray().size
        assertTrue(bytes < ChatWireLimits.MAX_REPLY_PARTS_BYTES + 1024)
    }
}
