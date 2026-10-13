package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.NativeFraming
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.SocketFrameLink
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LinkTransportTest {
    private val directory = Files.createTempDirectory("hb-link")
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        .bind(UnixDomainSocketAddress.of(directory.resolve("s.sock")))
    private val client = SocketChannel.open(UnixDomainSocketAddress.of(directory.resolve("s.sock")))
    private val accepted = server.accept()
    private val transport = LinkTransport(SocketFrameLink(accepted), Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        client.close()
        accepted.close()
        server.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `frames pass both ways and a clean close ends receiving`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            NativeFraming.write(Channels.newOutputStream(client), "in".encodeToByteArray())
            assertContentEquals("in".encodeToByteArray(), transport.receive())

            transport.send("out".encodeToByteArray())
            assertEquals("out", NativeFraming.read(Channels.newInputStream(client), 16)?.decodeToString())

            client.close()
            assertNull(transport.receive())
        }
    }

    @Test
    fun `an out of protocol frame ends the connection`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val header = (BridgeProtocol.MAX_FROM_BROWSER_BYTES + 1).let {
                byteArrayOf(it.toByte(), (it shr 8).toByte(), (it shr 16).toByte(), (it shr 24).toByte())
            }
            Channels.newOutputStream(client).write(header)

            assertNull(transport.receive())
        }
    }

    @Test
    fun `sending to a closed peer is reported as unavailable`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            accepted.close()

            val error = assertFailsWith<BridgeException> { transport.send("x".encodeToByteArray()) }

            assertEquals(BridgeErrorCode.UNAVAILABLE, error.code)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
