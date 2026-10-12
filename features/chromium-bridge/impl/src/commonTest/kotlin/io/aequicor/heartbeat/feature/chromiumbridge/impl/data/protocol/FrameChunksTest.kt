package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameChunksTest {
    @Test
    fun `large frame splits into chunks that fit the browser cap and reassemble`() {
        val frame = BridgeFrame.Event("big", buildJsonObject { put("text", "ж🙂".repeat(400_000)) })
        val payload = encodeFrame(frame)

        val chunks = chunkFrame("c:1", payload)

        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(encodeFrame(it).size < BridgeProtocol.MAX_TO_BROWSER_BYTES) }
        val assembler = ChunkAssembler()
        val joined = chunks.mapNotNull(assembler::accept).single()
        assertContentEquals(payload, joined)
        assertEquals(frame, decodeFrame(joined))
        assertEquals(0, assembler.pending)
    }

    @Test
    fun `multi byte characters split between slices are restored`() {
        val payload = "ж".repeat(5).encodeToByteArray()

        val chunks = chunkFrame("c:2", payload, sliceBytes = 3)

        assertEquals(4, chunks.size)
        val assembler = ChunkAssembler()
        val joined = chunks.reversed().mapNotNull(assembler::accept).single()
        assertEquals("ж".repeat(5), joined.decodeToString())
    }

    @Test
    fun `empty payload is a single chunk`() {
        val chunks = chunkFrame("c:3", ByteArray(0))

        assertEquals(listOf(BridgeFrame.Chunk("c:3", 0, 1, "")), chunks)
        assertContentEquals(ByteArray(0), ChunkAssembler().accept(chunks.single()))
    }

    @Test
    fun `inconsistent chunks drop their set`() {
        val assembler = ChunkAssembler()
        assertNull(assembler.accept(BridgeFrame.Chunk("c:4", 0, 2, "YQ==")))

        assertFailsWith<IllegalArgumentException> { assembler.accept(BridgeFrame.Chunk("c:4", 0, 2, "YQ==")) }
        assertEquals(0, assembler.pending)
        assertFailsWith<IllegalArgumentException> { assembler.accept(BridgeFrame.Chunk("c:5", 2, 2, "YQ==")) }
        assertNull(assembler.accept(BridgeFrame.Chunk("c:6", 0, 2, "YQ==")))
        assertFailsWith<IllegalArgumentException> { assembler.accept(BridgeFrame.Chunk("c:6", 1, 3, "YQ==")) }
        assertEquals(0, assembler.pending)
    }

    @Test
    fun `assembled size is capped`() {
        val assembler = ChunkAssembler(maxBytes = 2)

        assertFailsWith<IllegalArgumentException> {
            chunkFrame("c:7", "abc".encodeToByteArray(), sliceBytes = 1).forEach { assembler.accept(it) }
        }
        assertEquals(0, assembler.pending)
    }

    @Test
    fun `oldest open set is evicted beyond the limit`() {
        val assembler = ChunkAssembler(maxSets = 2)
        assembler.accept(BridgeFrame.Chunk("a", 0, 2, "YQ=="))
        assembler.accept(BridgeFrame.Chunk("b", 0, 2, "YQ=="))
        assembler.accept(BridgeFrame.Chunk("c", 0, 2, "YQ=="))

        assertEquals(2, assembler.pending)
        assertNull(assembler.accept(BridgeFrame.Chunk("a", 1, 2, "YQ==")))
    }
}
