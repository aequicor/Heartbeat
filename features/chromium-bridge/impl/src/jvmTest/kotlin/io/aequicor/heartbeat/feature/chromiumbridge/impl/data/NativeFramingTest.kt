package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NativeFramingTest {
    @Test
    fun `frames round trip with a little endian length prefix`() {
        val output = ByteArrayOutputStream()
        NativeFraming.write(output, "{\"a\":1}".encodeToByteArray())
        NativeFraming.write(output, ByteArray(300) { 1 })

        val bytes = output.toByteArray()
        assertContentEquals(byteArrayOf(7, 0, 0, 0), bytes.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(44, 1, 0, 0), bytes.copyOfRange(11, 15))
        val input = ByteArrayInputStream(bytes)
        assertEquals("{\"a\":1}", NativeFraming.read(input, 1024)?.decodeToString())
        assertEquals(300, NativeFraming.read(input, 1024)?.size)
        assertNull(NativeFraming.read(input, 1024))
    }

    @Test
    fun `stream ending inside a frame is an error`() {
        assertFailsWith<EOFException> { NativeFraming.read(ByteArrayInputStream(byteArrayOf(5, 0)), 1024) }
        assertFailsWith<EOFException> {
            NativeFraming.read(ByteArrayInputStream(byteArrayOf(5, 0, 0, 0, 1, 2)), 1024)
        }
    }

    @Test
    fun `announced length above the limit is rejected before reading the body`() {
        val header = byteArrayOf(0, 0, 0, 0x7F)

        val error = assertFailsWith<FrameTooLargeException> { NativeFraming.read(ByteArrayInputStream(header), 1024) }

        assertEquals(0x7F000000L, error.length)
    }

    @Test
    fun `lengths above the signed int range are rejected`() {
        val header = byteArrayOf(-1, -1, -1, -1)

        val error = assertFailsWith<FrameTooLargeException> { NativeFraming.read(ByteArrayInputStream(header), 1024) }

        assertEquals(0xFFFFFFFFL, error.length)
    }
}
