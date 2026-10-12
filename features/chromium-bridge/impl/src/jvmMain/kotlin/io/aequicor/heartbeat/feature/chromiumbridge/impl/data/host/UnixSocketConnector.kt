package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.FrameLink
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.SocketFrameLink
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/** Connects to the app's Unix domain socket; a missing socket file means Heartbeat is not listening. */
internal class UnixSocketConnector(private val path: Path) : AppConnector {
    override fun connect(): FrameLink? {
        if (!Files.exists(path)) return null
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(path))
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        return SocketFrameLink(channel)
    }
}
