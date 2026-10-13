package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFeatureSet
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChunkAssembler
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.decodeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.encodeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeConnectionTest {
    private val origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/"
    private val transport = FakeTransport()
    private val echo = object : BridgeRequestHandler {
        override val methods = setOf("echo")
        override suspend fun handle(call: BridgeCall): BridgeReply = BridgeReply.Ok(call.params)
    }
    private val strict = object : BridgeRequestHandler {
        override val methods = setOf("strict")
        override suspend fun handle(call: BridgeCall): BridgeReply = throw IllegalArgumentException("secret input")
    }
    private val hello = HelloHandler("1.2.3") { BridgeFeatureSet(isChat = true, isAgentControl = false) }
    private val connections = BridgeConnections(BridgeDispatcher(setOf(hello, echo, strict))) { it == origin }

    @Test
    fun `watch activation follows the response even with more than 64 immediate events`() = runTest {
        val streaming = object : BridgeRequestHandler {
            override val methods = setOf("stream")
            override suspend fun handle(call: BridgeCall): BridgeReply {
                val delivered = CompletableDeferred<Boolean>()
                call.session.scope.launch {
                    if (delivered.await()) {
                        repeat(72) { index ->
                            call.session.emit("studio.changed", buildJsonObject { put("index", index) })
                        }
                    }
                }
                return BridgeReply.Ok(JsonPrimitive("w:1")) { delivered.complete(it) }
            }
        }
        val server = BridgeConnections(BridgeDispatcher(setOf(hello, streaming))) { it == origin }
        backgroundScope.launch { server.serve(transport) }
        transport.deliver(BridgeFrame.Host(origin = origin))
        transport.deliver(helloRequest(listOf(1)))
        transport.next<BridgeFrame.Response>()
        transport.deliver(BridgeFrame.Request("stream:1", "stream"))
        runCurrent()
        assertEquals("stream:1", transport.next<BridgeFrame.Response>().id)
        repeat(72) { index ->
            assertEquals(index, transport.next<BridgeFrame.Event>().params.getValue("index").jsonPrimitive.int)
        }
        transport.close()
    }

    @Test
    fun `response failure and cancellation notify the reserved producer`() = runTest {
        val results = mutableListOf<Boolean>()
        val watched = object : BridgeRequestHandler {
            override val methods = setOf("watch")
            override suspend fun handle(call: BridgeCall): BridgeReply =
                BridgeReply.Ok(JsonPrimitive("w:1")) { results += it }
        }
        val server = BridgeConnections(BridgeDispatcher(setOf(hello, watched))) { it == origin }
        backgroundScope.launch { server.serve(transport) }
        transport.deliver(BridgeFrame.Host(origin = origin))
        transport.deliver(helloRequest(listOf(1)))
        transport.next<BridgeFrame.Response>()
        for (failure in listOf(BridgeException("unavailable", "Lost"), CancellationException("Cancelled"))) {
            transport.sendFailure = failure
            transport.deliver(BridgeFrame.Request("w", "watch"))
            runCurrent()
        }
        assertEquals(listOf(false, false), results)
        transport.close()
    }

    @Test
    fun `unknown origin is closed before any request`() = runTest {
        val served = async { connections.serve(transport) }
        transport.deliver(BridgeFrame.Host(origin = "chrome-extension://pponmlkjihgfedcbapponmlkjihgfedcb/"))

        served.await()

        assertTrue(connections.sessions.value.isEmpty())
        assertNull(transport.sent.tryReceive().getOrNull())
    }

    @Test
    fun `requests before hello are refused as not ready`() = runTest {
        connect()

        transport.deliver(BridgeFrame.Request("e:1", "echo"))

        assertEquals(BridgeErrorCode.NOT_READY, transport.next<BridgeFrame.Response>().error?.code)
        transport.close()
    }

    @Test
    fun `hello records the client and returns features and limits`() = runTest {
        val session = connect()

        transport.deliver(helloRequest(protocols = listOf(1, 2)))

        val result = transport.next<BridgeFrame.Response>().result?.jsonObject
        assertEquals(1, result?.get("protocol")?.jsonPrimitive?.int)
        assertEquals("1.2.3", result?.get("app")?.jsonObject?.get("version")?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), result?.get("features")?.jsonObject?.get("chat"))
        assertEquals(setOf("chat"), session.client.value?.capabilities)
        assertEquals("Chrome", session.client.value?.browserBrand)
        transport.close()
    }

    @Test
    fun `unsupported protocol is refused and the connection stays usable`() = runTest {
        val session = connect()

        transport.deliver(helloRequest(protocols = listOf(7)))

        assertEquals(BridgeErrorCode.UNSUPPORTED_PROTOCOL, transport.next<BridgeFrame.Response>().error?.code)
        assertNull(session.client.value)
        transport.deliver(helloRequest(protocols = listOf(1)))
        assertNotNull(transport.next<BridgeFrame.Response>().result)
        transport.close()
    }

    @Test
    fun `unknown methods and invalid params are reported with codes`() = runTest {
        handshake()

        transport.deliver(BridgeFrame.Request("e:2", "missing"))
        assertEquals(BridgeErrorCode.UNKNOWN_METHOD, transport.next<BridgeFrame.Response>().error?.code)
        transport.deliver(BridgeFrame.Request("e:3", "strict"))
        val refused = transport.next<BridgeFrame.Response>().error
        assertEquals(BridgeErrorCode.BAD_REQUEST, refused?.code)
        assertTrue("secret" !in refused?.message.orEmpty())
        transport.deliver(BridgeFrame.Request("e:4", "echo", buildJsonObject { put("a", 1) }))
        assertEquals(buildJsonObject { put("a", 1) }, transport.next<BridgeFrame.Response>().result)
        transport.close()
    }

    @Test
    fun `app calls are correlated by id and fail on timeout or disconnect`() = runTest {
        val session = handshake()

        val answered = async { session.call("tabs.active", JsonObject(emptyMap())) }
        val request = transport.next<BridgeFrame.Request>()
        assertTrue(request.id.startsWith("a:"))
        transport.deliver(BridgeFrame.Response(request.id, result = JsonPrimitive("ok")))
        assertEquals(JsonPrimitive("ok"), answered.await())

        val timedOut = async {
            assertFailsWith<BridgeException> { session.call("tabs.active", JsonObject(emptyMap()), 1_000) }
        }
        transport.next<BridgeFrame.Request>()
        advanceTimeBy(1_001)
        assertEquals(BridgeErrorCode.TIMEOUT, timedOut.await().code)

        val abandoned = async {
            assertFailsWith<BridgeException> {
                session.call(
                    "tabs.active",
                    JsonObject(emptyMap()),
                )
            }
        }
        transport.next<BridgeFrame.Request>()
        transport.close()
        assertEquals(BridgeErrorCode.UNAVAILABLE, abandoned.await().code)
    }

    @Test
    fun `extension errors surface as bridge exceptions`() = runTest {
        val session = handshake()

        val failed = async { assertFailsWith<BridgeException> { session.call("cdp.send", JsonObject(emptyMap())) } }
        val request = transport.next<BridgeFrame.Request>()
        transport.deliver(BridgeFrame.Response(request.id, error = BridgeFrameError("forbidden", "Not allowed")))

        assertEquals("forbidden", failed.await().code)
        transport.close()
    }

    @Test
    fun `events above the browser cap are chunked`() = runTest {
        val session = handshake()
        val text = "x".repeat(BridgeProtocol.MAX_TO_BROWSER_BYTES)

        launch { session.emit("chat.changed", buildJsonObject { put("text", text) }) }
        runCurrent()

        val assembler = ChunkAssembler()
        var joined: ByteArray? = null
        while (joined == null) {
            val payload = transport.sent.receive()
            assertTrue(payload.size < BridgeProtocol.MAX_TO_BROWSER_BYTES)
            joined = assembler.accept(decodeFrame(payload) as BridgeFrame.Chunk)
        }
        val event = decodeFrame(joined) as BridgeFrame.Event
        assertEquals(text, event.params["text"]?.jsonPrimitive?.content)
        transport.close()
    }

    @Test
    fun `capability changes update the client and reach event consumers`() = runTest {
        val session = handshake()
        val events = Channel<BridgeIncomingEvent>(Channel.UNLIMITED)
        backgroundScope.launch { session.events.collect { events.send(it) } }
        runCurrent()

        transport.deliver(
            BridgeFrame.Event(
                "capabilities.changed",
                buildJsonObject { putJsonArray("capabilities") { add(JsonPrimitive("agentControl")) } },
            ),
        )

        assertEquals("capabilities.changed", events.receive().event)
        assertEquals(setOf("agentControl"), session.client.value?.capabilities)
        transport.close()
    }

    @Test
    fun `closing the connection removes the session`() = runTest {
        handshake()
        assertEquals(1, connections.sessions.value.size)

        transport.close()
        runCurrent()

        assertTrue(connections.sessions.value.isEmpty())
    }

    @Test
    fun `methods claimed twice are a configuration error`() {
        assertFailsWith<IllegalStateException> { BridgeDispatcher(setOf(echo, echo.copyOf())) }
    }

    private fun BridgeRequestHandler.copyOf(): BridgeRequestHandler = object : BridgeRequestHandler {
        override val methods = this@copyOf.methods
        override suspend fun handle(call: BridgeCall): BridgeReply = BridgeReply.Ok(JsonPrimitive(true))
    }

    private suspend fun TestScope.connect(): BridgeSession {
        backgroundScope.launch { connections.serve(transport) }
        transport.deliver(BridgeFrame.Host(origin = origin, hostVersion = 1))
        runCurrent()
        return connections.sessions.value.single()
    }

    private suspend fun TestScope.handshake(): BridgeSession = connect().also {
        transport.deliver(helloRequest(protocols = listOf(1)))
        transport.next<BridgeFrame.Response>()
    }

    private fun helloRequest(protocols: List<Int>) = BridgeFrame.Request(
        "e:hello",
        "hello",
        buildJsonObject {
            put("protocol", buildJsonArray { protocols.forEach { add(JsonPrimitive(it)) } })
            putJsonObject("extension") { put("version", "0.1.0") }
            putJsonObject("browser") {
                put("brand", "Chrome")
                put("version", "140")
            }
            putJsonArray("capabilities") { add(JsonPrimitive("chat")) }
        },
    )
}

private typealias BridgeFrameError = io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeError

/** In-memory transport: the test plays the host and the extension. */
internal class FakeTransport : FrameTransport {
    private val incoming = Channel<ByteArray?>(Channel.UNLIMITED)
    val sent = Channel<ByteArray>(Channel.UNLIMITED)
    var sendFailure: Exception? = null

    override suspend fun receive(): ByteArray? = incoming.receive()

    override suspend fun send(payload: ByteArray) {
        sendFailure?.let { throw it }
        sent.send(payload)
    }

    suspend fun deliver(frame: BridgeFrame) {
        incoming.send(encodeFrame(frame))
    }

    suspend fun close() {
        incoming.send(null)
    }

    suspend inline fun <reified T : BridgeFrame> next(): T = decodeFrame(sent.receive()) as T
}
