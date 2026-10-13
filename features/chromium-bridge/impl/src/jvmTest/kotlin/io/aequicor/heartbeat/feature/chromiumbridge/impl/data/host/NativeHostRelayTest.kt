package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.ChannelInputStream
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.ChannelOutputStream
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.NativeFraming
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HostState
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.decodeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.encodeFrame
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeHostRelayTest {
    private val directory = Files.createTempDirectory("hb-host")
    private val socket = directory.resolve("bridge.sock")
    private val origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/"
    private val toHost = PipedOutputStream()
    private val hostInput = PipedInputStream(toHost, PIPE_BYTES)
    private val hostOutput = PipedOutputStream()
    private val fromHost = PipedInputStream(hostOutput, PIPE_BYTES)
    private val executor = Executors.newCachedThreadPool()
    private var server: ServerSocketChannel? = null

    @AfterTest
    fun tearDown() {
        server?.close()
        executor.shutdownNow()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `without Heartbeat the host reports waiting and exits when the browser closes`() {
        val exit = startRelay()

        assertEquals(BridgeFrame.Host(state = HostState.Waiting), readBrowser())
        toHost.close()

        assertEquals(NativeHostRelay.EXIT_OK, exit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `host introduces the origin and relays frames both ways`() {
        val accepted = listen()
        val exit = startRelay()
        assertEquals(BridgeFrame.Host(state = HostState.Waiting), readBrowser())
        val app = accepted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertEquals(BridgeFrame.Host(origin = origin, hostVersion = NativeHostRelay.HOST_VERSION), app.read())
        assertEquals(BridgeFrame.Host(state = HostState.Connected), readBrowser())
        writeBrowser(BridgeFrame.Request("e:1", "hello"))
        assertEquals(BridgeFrame.Request("e:1", "hello"), app.read())
        app.write(BridgeFrame.Response("e:1", error = null))
        assertEquals(BridgeFrame.Response("e:1"), readBrowser())

        toHost.close()
        assertEquals(NativeHostRelay.EXIT_OK, exit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `app frames above the browser cap are dropped without breaking the stream`() {
        val accepted = listen()
        startRelay()
        readBrowser()
        val app = accepted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        app.read()
        readBrowser()

        app.writeRaw(ByteArray(BridgeProtocol.MAX_TO_BROWSER_BYTES + 1) { ' '.code.toByte() })
        app.write(BridgeFrame.Event("after"))

        assertEquals(BridgeFrame.Event("after"), readBrowser())
        toHost.close()
    }

    @Test
    fun `losing the app reports waiting again and the host reconnects`() {
        var accepted = listen()
        startRelay()
        readBrowser()
        accepted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).also { it.read() }.close()
        assertEquals(BridgeFrame.Host(state = HostState.Connected), readBrowser())

        assertEquals(BridgeFrame.Host(state = HostState.Waiting), readBrowser())
        accepted = acceptNext()
        val second = accepted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(second.read() is BridgeFrame.Host)
        assertEquals(BridgeFrame.Host(state = HostState.Connected), readBrowser())
        toHost.close()
    }

    private fun startRelay(): CompletableFuture<Int> = CompletableFuture.supplyAsync(
        {
            NativeHostRelay(hostInput, hostOutput, origin, UnixSocketConnector(socket)) { RETRY_MILLIS }.run()
        },
        executor,
    )

    private fun listen(): CompletableFuture<AppEnd> {
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
        return acceptNext()
    }

    private fun acceptNext(): CompletableFuture<AppEnd> {
        val listening = checkNotNull(server)
        return CompletableFuture.supplyAsync({ AppEnd(listening.accept()) }, executor)
    }

    private fun readBrowser(): BridgeFrame? = CompletableFuture.supplyAsync(
        { NativeFraming.read(fromHost, Int.MAX_VALUE)?.let(::decodeFrame) },
        executor,
    ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun writeBrowser(frame: BridgeFrame) {
        NativeFraming.write(toHost, encodeFrame(frame))
    }

    private inner class AppEnd(private val channel: SocketChannel) {
        private val input: InputStream = ChannelInputStream(channel)
        private val output: OutputStream = ChannelOutputStream(channel)

        fun read(): BridgeFrame? = CompletableFuture.supplyAsync(
            { NativeFraming.read(input, Int.MAX_VALUE)?.let(::decodeFrame) },
            executor,
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        fun write(frame: BridgeFrame) = writeRaw(encodeFrame(frame))

        fun writeRaw(payload: ByteArray) = NativeFraming.write(output, payload)

        fun close() = channel.close()
    }

    private companion object {
        const val PIPE_BYTES = 4 * 1024 * 1024
        const val RETRY_MILLIS = 20L
        const val TIMEOUT_SECONDS = 10L
    }
}
