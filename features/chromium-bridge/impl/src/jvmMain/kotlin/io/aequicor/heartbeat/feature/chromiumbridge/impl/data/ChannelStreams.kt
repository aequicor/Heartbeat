package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Blocking stream over a socket channel that never takes the channel's blocking lock, unlike
 * `Channels.newInputStream`: one thread can wait for input while another writes to the same channel.
 */
internal class ChannelInputStream(private val channel: ReadableByteChannel) : InputStream() {
    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return channel.read(ByteBuffer.wrap(buffer, offset, length))
    }

    override fun close() {
        channel.close()
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}

/** Writing counterpart of [ChannelInputStream]; writes the whole range before returning. */
internal class ChannelOutputStream(private val channel: WritableByteChannel) : OutputStream() {
    override fun write(byte: Int) {
        write(byteArrayOf(byte.toByte()), 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val bytes = ByteBuffer.wrap(buffer, offset, length)
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    override fun close() {
        channel.close()
    }
}
