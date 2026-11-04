package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CodexRpcTest {
    @Test
    fun `initialization handshake precedes other requests`() = runTest {
        val wire = FakeWire()
        val rpc = CodexRpc(wire, backgroundScope)
        rpc.initialize()
        rpc.request("model/list")
        assertEquals(listOf("initialize", "initialized", "model/list"), wire.written.map { it.text("method") })
        rpc.close()
    }

    @Test
    fun `concurrent replies are correlated by request id`() = runTest {
        val wire = FakeWire()
        val requests = mutableListOf<JsonObject>()
        wire.handler = { message ->
            requests += message
            if (requests.size == 2) {
                wire.reply(requests[1], json("value" to "second".json()))
                wire.reply(requests[0], json("value" to "first".json()))
            }
        }
        val rpc = CodexRpc(wire, backgroundScope)
        val first = async { rpc.request("one") }
        val second = async { rpc.request("two") }
        assertEquals("first", first.await().text("value"))
        assertEquals("second", second.await().text("value"))
        rpc.close()
    }

    @Test
    fun `timeout is explicit and does not expose native payloads`() = runTest {
        val wire = FakeWire()
        wire.handler = { }
        val rpc = CodexRpc(wire, backgroundScope)
        val error = assertFailsWith<EngineException> { rpc.request("unanswered") }
        assertEquals(EngineFailure.Transport(TransportFailureReason.Timeout), error.failure)
        assertFalse(wire.isClosed)
        rpc.close()
    }

    @Test
    fun `handshake reports the held process version without probing the file`() = runTest {
        val wire = FakeWire()
        wire.handler = { message ->
            if (message.text("method") == "initialize") {
                wire.reply(
                    message,
                    json("userAgent" to "heartbeat/0.163.0-alpha.10.2 (Mac OS; arm64)".json()),
                )
            }
        }
        val rpc = CodexRpc(wire, backgroundScope)
        rpc.initialize()
        assertEquals("0.163.0-alpha.10.2", rpc.engineVersion)
        rpc.close()
    }

    @Test
    fun `handshake without an agent leaves the version unknown`() = runTest {
        val wire = FakeWire()
        val rpc = CodexRpc(wire, backgroundScope)
        rpc.initialize()
        assertNull(rpc.engineVersion)
        rpc.close()
    }

    @Test
    fun `non-string agent is a protocol violation instead of an unknown version`() = runTest {
        val wire = FakeWire()
        wire.handler = { message ->
            if (message.text("method") == "initialize") {
                wire.reply(message, json("userAgent" to JsonPrimitive(5)))
            }
        }
        val rpc = CodexRpc(wire, backgroundScope)
        val error = assertFailsWith<EngineException> { rpc.initialize() }
        assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), error.failure)
        rpc.close()
    }
}
