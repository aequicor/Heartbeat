package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteOpen
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemotePage
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.FrameLink
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.NativeFraming
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.SocketFrameLink
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.ToggleBridgeFeatures
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChunkAssembler
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.decodeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.encodeFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DesktopBridgeServerTest {
    private val directory = Files.createTempDirectory("hb-bridge")
    private val socket = directory.resolve("bridge.sock")

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `large replies and watch scopes stay off the UI dispatcher`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val callingThread = Thread.currentThread()
            val completedOn = CompletableDeferred<Thread>()
            val handlerDispatcher = CompletableDeferred<ContinuationInterceptor?>()
            val watchDispatcher = CompletableDeferred<ContinuationInterceptor?>()
            val text = "x".repeat(BridgeProtocol.MAX_TO_BROWSER_BYTES)
            val handler = object : BridgeRequestHandler {
                override val methods = setOf("large")
                override val isHandshake = true

                override suspend fun handle(call: BridgeCall): BridgeReply {
                    handlerDispatcher.complete(currentCoroutineContext()[ContinuationInterceptor])
                    watchDispatcher.complete(call.session.scope.coroutineContext[ContinuationInterceptor])
                    return BridgeReply.Ok(JsonPrimitive(text)) { delivered ->
                        assertTrue(delivered)
                        completedOn.complete(Thread.currentThread())
                    }
                }
            }
            val connections = BridgeConnections(BridgeDispatcher(setOf(handler))) { true }
            val features = ToggleBridgeFeatures(DefaultToggles(), UnavailableStudio())
            val server = DesktopBridgeServer({ socket }, connections, features, Dispatchers.IO)
            val listening = CompletableDeferred<Unit>()
            // Deliberately inherit the caller's thread, as the profile's main-bound machine effect does.
            val job = launch { server.serve { if (it == BridgeServerStatus.Listening) listening.complete(Unit) } }
            try {
                listening.await()
                val client = runInterruptible(Dispatchers.IO) {
                    SocketFrameLink(SocketChannel.open(UnixDomainSocketAddress.of(socket)))
                }
                try {
                    val response = runInterruptible(Dispatchers.IO) { exchange(client) }
                    assertEquals(JsonPrimitive(text), response.result)
                    assertEquals(Dispatchers.IO, handlerDispatcher.await())
                    assertEquals(Dispatchers.IO, watchDispatcher.await())
                    assertNotSame(callingThread, completedOn.await())
                } finally {
                    client.close()
                }
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private fun exchange(client: FrameLink): BridgeFrame.Response {
        NativeFraming.write(client.output, encodeFrame(BridgeFrame.Host(origin = "test")))
        NativeFraming.write(client.output, encodeFrame(BridgeFrame.Request("large:1", "large")))
        val assembler = ChunkAssembler()
        var frame: ByteArray? = null
        while (frame == null) {
            val payload = checkNotNull(NativeFraming.read(client.input, BridgeProtocol.MAX_TO_BROWSER_BYTES))
            assertTrue(payload.size < BridgeProtocol.MAX_TO_BROWSER_BYTES)
            frame = assembler.accept(assertIs<BridgeFrame.Chunk>(decodeFrame(payload)))
        }
        return assertIs<BridgeFrame.Response>(decodeFrame(frame))
    }

    private class DefaultToggles : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(toggle.default)
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = toggle.default
    }

    private class UnavailableStudio : StudioRemoteScreens {
        override val availability = flowOf(false)

        override suspend fun open(
            viewId: String,
            schema: String,
            environment: StudioChatEnvironment?,
            owner: String,
        ): StudioRemoteOpen = error("unused")

        override suspend fun send(
            viewId: String,
            generation: String,
            intents: List<StudioRemoteIntent>,
            owner: String,
        ): StudioRemoteReply = error("unused")

        override suspend fun attachPage(
            viewId: String,
            generation: String,
            seq: Long,
            paneId: Int,
            page: StudioRemotePage,
            owner: String,
        ): StudioRemoteReply = error("unused")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
