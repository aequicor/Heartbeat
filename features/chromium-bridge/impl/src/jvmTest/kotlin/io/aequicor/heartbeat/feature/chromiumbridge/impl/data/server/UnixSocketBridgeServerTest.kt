package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.FrameLink
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.NativeFraming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UnixSocketBridgeServerTest {
    private val directory = Files.createTempDirectory("hb-server")
    private val socket = directory.resolve("run").resolve("bridge.sock")

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `connections are served until cancellation removes the socket`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val listening = CompletableDeferred<Unit>()
            val received = Channel<String>(Channel.UNLIMITED)
            val server = UnixSocketBridgeServer(socket, Dispatchers.IO)
            val job = launch(Dispatchers.IO) {
                server.serve(onListening = { listening.complete(Unit) }) { link -> echo(link, received) }
            }
            listening.await()

            val client = connect()
            NativeFraming.write(Channels.newOutputStream(client), "ping".encodeToByteArray())

            assertEquals("ping", received.receive())
            assertEquals("ping", runInterruptible(Dispatchers.IO) { readFrame(client) })
            job.cancelAndJoin()
            assertFalse(Files.exists(socket))
        }
    }

    @Test
    fun `stale socket file is replaced`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                Files.createDirectories(socket.parent)
                bind(UnixDomainSocketAddress.of(socket))
                close()
            }
            assertTrue(Files.exists(socket))
            val listening = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                UnixSocketBridgeServer(socket, Dispatchers.IO).serve(onListening = { listening.complete(Unit) }) {}
            }

            listening.await()
            connect().close()
            job.cancelAndJoin()
        }
    }

    @Test
    fun `live socket of another process is never taken over`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            Files.createDirectories(socket.parent)
            val other = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
            val acceptor = launch(Dispatchers.IO) { runInterruptible { other.accept().close() } }
            try {
                val result = UnixSocketBridgeServer(socket, Dispatchers.IO).serve(onListening = {}) {}

                assertEquals(ServeResult.Occupied, result)
                assertTrue(Files.exists(socket))
                // The probe sits in the backlog until accepted; interrupting accept() fails the acceptor.
                acceptor.join()
            } finally {
                acceptor.cancelAndJoin()
                other.close()
            }
        }
    }

    @Test
    fun `connections above the limit are closed immediately`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val listening = CompletableDeferred<Unit>()
            val counts = Channel<Int>(Channel.UNLIMITED)
            val hold = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                UnixSocketBridgeServer(socket, Dispatchers.IO, maxConnections = 1).serve(
                    onListening = { listening.complete(Unit) },
                    onConnections = { counts.trySend(it) },
                ) { hold.await() }
            }
            listening.await()
            val first = connect()
            assertEquals(1, counts.receive())

            val second = connect()
            val read = async(Dispatchers.IO) { runInterruptible { second.read(ByteBuffer.allocate(1)) } }

            assertEquals(-1, read.await())
            hold.complete(Unit)
            assertEquals(0, counts.receive())
            first.close()
            job.cancelAndJoin()
        }
    }

    @Test
    fun `unwritable location fails without listening`() {
        Files.writeString(directory.resolve("run"), "not a directory")

        val result = runBlocking { UnixSocketBridgeServer(socket, Dispatchers.IO).serve(onListening = {}) {} }

        assertIs<ServeResult.Failed>(result)
    }

    @Test
    fun `bind failure closes the allocated channel`() = runBlocking {
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val tooLong = directory.resolve("x".repeat(200))
        try {
            val result = UnixSocketBridgeServer(tooLong, Dispatchers.IO, openChannel = { channel })
                .serve(onListening = {}) {}

            assertIs<ServeResult.Failed>(result)
            assertFalse(channel.isOpen)
        } finally {
            channel.close()
        }
    }

    private suspend fun echo(link: FrameLink, received: Channel<String>) = runInterruptible(Dispatchers.IO) {
        val frame = checkNotNull(NativeFraming.read(link.input, Int.MAX_VALUE))
        received.trySend(frame.decodeToString())
        NativeFraming.write(link.output, frame)
    }

    private fun connect(): SocketChannel = SocketChannel.open(UnixDomainSocketAddress.of(socket))

    private fun readFrame(client: SocketChannel): String? =
        NativeFraming.read(Channels.newInputStream(client), Int.MAX_VALUE)?.decodeToString()

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
