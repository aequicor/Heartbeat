package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Chrome native-messaging framing: a 4-byte little-endian length followed by that many payload bytes. The host
 * and the app socket use the same framing, so the host relays frames without parsing their JSON.
 */
internal object NativeFraming {
    private const val HEADER_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /**
     * Reads one payload. Returns `null` on a clean end of stream before a header starts.
     * @throws EOFException when the stream ends inside a frame.
     * @throws FrameTooLargeException when the announced length exceeds [maxBytes]; the stream is unusable after it.
     */
    fun read(input: InputStream, maxBytes: Int): ByteArray? {
        val header = ByteArray(HEADER_BYTES)
        val headerRead = input.readNBytes(header, 0, HEADER_BYTES)
        if (headerRead == 0) return null
        val length = decodeLength(header, headerRead)
        if (length > maxBytes) throw FrameTooLargeException(length, maxBytes)
        val payload = ByteArray(length.toInt())
        if (input.readNBytes(payload, 0, payload.size) < payload.size) {
            throw EOFException("Stream ended inside a frame body")
        }
        return payload
    }

    private fun decodeLength(header: ByteArray, read: Int): Long {
        if (read < HEADER_BYTES) throw EOFException("Stream ended inside a frame header")
        var length = 0L
        for (index in HEADER_BYTES - 1 downTo 0) {
            length = (length shl BITS_PER_BYTE) or (header[index].toLong() and BYTE_MASK.toLong())
        }
        return length
    }

    /** Writes one frame and flushes; callers serialize writers of the same stream. */
    fun write(output: OutputStream, payload: ByteArray) {
        val header = ByteArray(HEADER_BYTES)
        var length = payload.size
        for (index in 0 until HEADER_BYTES) {
            header[index] = (length and BYTE_MASK).toByte()
            length = length ushr BITS_PER_BYTE
        }
        output.write(header)
        output.write(payload)
        output.flush()
    }
}

/** An announced frame length above the receiver's limit; the peer is out of protocol. */
internal class FrameTooLargeException(val length: Long, val limit: Int) :
    IOException("Frame of $length bytes exceeds the $limit-byte limit")
