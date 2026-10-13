package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioViewTransferTest {
    @Test
    fun `a snapshot larger than native reassembly reaches the mirror atomically`() {
        val text = "Я🚀".repeat(3_000_000)
        val state = AiStudioScreenState().withDraft(0, text)
        val packets = StudioViewWriter().write(state, 0).toList()
        assertTrue(packets.size > 64)
        val reader = StudioViewReader()
        packets.dropLast(1).forEach { packet ->
            assertTrue(packet.toString().encodeToByteArray().size < 900_000)
            assertNull(reader.read(packet))
        }
        val restored = checkNotNull(reader.read(packets.last()))
        assertEquals(text, StudioViewMirror().apply(restored).draft(0))
    }

    @Test
    fun `a missing or repeated part cannot publish a partial frame and reset allows recovery`() {
        val state = AiStudioScreenState().withDraft(0, "x".repeat(600_000))
        val parts = StudioViewWriter().write(state, 0).toList()
        val reader = StudioViewReader()
        assertFailsWith<IllegalArgumentException> { reader.read(parts[1]) }
        reader.reset()
        assertNull(reader.read(parts[0]))
        assertFailsWith<IllegalArgumentException> { reader.read(parts[0]) }
        reader.reset()
        assertEquals(state, StudioViewMirror().apply(parts.mapNotNull(reader::read).single()))
    }

    @Test
    fun `an oversized state is reported without ack and the same watch recovers with a snapshot`() {
        val writer = StudioViewWriter(maxBytes = 10_000)
        val reader = StudioViewReader()
        val mirror = StudioViewMirror()
        val state = AiStudioScreenState()
        val first = writer.write(state, 0).mapNotNull(reader::read).single()
        mirror.apply(first)
        val refused = writer.write(state.withDraft(0, "x".repeat(20_000)), 1).single()
        assertFailsWith<StudioViewLimitException> { reader.read(refused) }
        assertEquals(first.rev, mirror.revision)
        val next = writer.write(state.withDraft(0, "recovered"), 2).mapNotNull(reader::read).single()
        assertTrue(next is StudioViewFrame.Snapshot)
        assertEquals(2, next.ack)
        assertEquals("recovered", mirror.apply(next).draft(0))
    }
}
