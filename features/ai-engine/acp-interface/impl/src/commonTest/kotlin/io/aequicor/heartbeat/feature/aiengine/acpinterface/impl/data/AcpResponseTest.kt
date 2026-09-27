package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpResponseTest {
    @Test
    fun `calls after disconnection fail with Disconnected instead of cancellation`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.transport.input.close()
        testScheduler.runCurrent()
        assertTrue(h.transport.isClosed)
        assertFailsWith<AcpException.Disconnected> { h.client.newSession("/workspace") }
        assertFailsWith<AcpException.Disconnected> { h.client.authenticate("login") }
    }

    @Test
    fun `malformed error object fails its caller with Protocol and closes connection`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val pending = backgroundScope.async {
            assertFailsWith<AcpException.Protocol> { h.client.newSession("/workspace") }
        }
        val request = h.outgoing("session/new")
        h.transport.input.send("""{"jsonrpc":"2.0","id":${request.id()},"error":{"message":"no code"}}""")
        pending.await()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `null result of a void method is accepted`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val authenticating = backgroundScope.async { h.client.authenticate("login") }
        h.reply(h.outgoing("authenticate"), "null")
        authenticating.await()
        assertFalse(h.transport.isClosed)
        h.client.close()
    }

    @Test
    fun `invalid prompt response fails with Protocol and closes connection`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val prompt = backgroundScope.async {
            assertFailsWith<AcpException.Protocol> { h.client.prompt("session", "hello") }
        }
        h.reply(h.outgoing("session/prompt"), "{}")
        prompt.await()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `blank native session id fails with Protocol`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val session = backgroundScope.async {
            assertFailsWith<AcpException.Protocol> { h.client.newSession("/workspace") }
        }
        h.reply(h.outgoing("session/new"), """{"sessionId":" "}""")
        session.await()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `invalid permission params are rejected without closing the connection`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.transport.input.send(
            """{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s"}}""",
        )
        val response = acpJson.parseToJsonElement(h.transport.output.receive()) as JsonObject
        assertEquals(7, response.number("id"))
        assertEquals(-32602, response.obj("error").number("code"))
        assertFalse(h.transport.isClosed)
        h.client.close()
    }
}
