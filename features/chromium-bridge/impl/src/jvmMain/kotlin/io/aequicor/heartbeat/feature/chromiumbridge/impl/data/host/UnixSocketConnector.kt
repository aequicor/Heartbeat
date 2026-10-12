package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.ChannelInputStream
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.ChannelOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/** Connects to the app's Unix domain socket; a missing socket file means Heartbeat is not listening. */
internal class UnixSocketConnector(private val path: Path) : AppConnector {
    override fun connect(): AppLink? {
        if (!Files.exists(path)) return null
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(path))
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        return SocketLink(channel)
    }

    private class SocketLink(private val channel: SocketChannel) : AppLink {
        override val input: InputStream = ChannelInputStream(channel)
        override val output: OutputStream = ChannelOutputStream(channel)

        override fun close() {
            channel.close()
        }
    }
}
