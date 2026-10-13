package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SocketChannel
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

/** A bidirectional framed connection: the app socket seen from the host, or a host seen from the app. */
internal interface FrameLink : Closeable {
    /** Incoming frames. */
    val input: InputStream

    /** Outgoing frames; writers serialize on the link. */
    val output: OutputStream
}

/** [FrameLink] over a connected socket channel. */
internal class SocketFrameLink(private val channel: SocketChannel) : FrameLink {
    override val input: InputStream = ChannelInputStream(channel)
    override val output: OutputStream = ChannelOutputStream(channel)

    override fun close() {
        channel.close()
    }
}
