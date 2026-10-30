package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpConnectionTest {
    @Test
    fun `new and load forward trusted MCP servers`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize("""{"loadSession":true}""")
        val server = Json.parseToJsonElement(
            """{"type":"http","name":"host","url":"http://127.0.0.1/mcp",""" +
                """"headers":[{"name":"Authorization","value":"Bearer secret"}]}""",
        ).jsonObject
        val creating = backgroundScope.async { h.client.newSession("/workspace", listOf(server)) }
        val create = h.outgoing("session/new")
        assertEquals(
            kotlinx.serialization.json.JsonArray(listOf(server)),
            create.obj("params")["mcpServers"] as kotlinx.serialization.json.JsonArray,
        )
        h.reply(create, """{"sessionId":"session"}""")
        creating.await()
        val loading = backgroundScope.async { h.client.loadSession("other", "/workspace", listOf(server)) }
        val load = h.outgoing("session/load")
        assertEquals(
            kotlinx.serialization.json.JsonArray(listOf(server)),
            load.obj("params")["mcpServers"] as kotlinx.serialization.json.JsonArray,
        )
        h.reply(load, "{}")
        loading.await()
        h.client.close()
    }

    @Test
    fun `initialization advertises no filesystem or terminal access`() = runTest {
        val h = AcpTestHarness(this)
        val result = backgroundScope.async { h.client.initialize(AcpImplementation("Heartbeat", "1")) }
        val request = h.outgoing("initialize")
        assertEquals(1, request.obj("params").number("protocolVersion"))
        assertTrue(request.obj("params").obj("clientCapabilities").isEmpty())
        h.reply(request, """{"protocolVersion":1,"agentCapabilities":{"future":true},"newField":42}""")
        assertTrue("future" in result.await().agentCapabilities)
        assertFailsWith<IllegalStateException> { h.client.initialize(AcpImplementation("test", "1")) }
        h.client.close()
    }

    @Test
    fun `calls before initialization are rejected without wire traffic`() = runTest {
        val h = AcpTestHarness(this)
        assertFailsWith<IllegalStateException> { h.client.newSession("/workspace") }
        assertTrue(h.transport.output.tryReceive().isFailure)
        h.client.close()
    }

    @Test
    fun `unsupported version closes connection`() = runTest {
        val h = AcpTestHarness(this)
        val result = backgroundScope.async {
            assertFailsWith<AcpException.Protocol> {
                h.client.initialize(
                    AcpImplementation("test", "1"),
                )
            }
        }
        h.reply(h.outgoing("initialize"), """{"protocolVersion":999}""")
        result.await()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `load is gated and missing capability does not imply empty history`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        assertFailsWith<IllegalStateException> { h.client.loadSession("native", "/workspace") }
        assertTrue(h.transport.output.tryReceive().isFailure)
        h.client.close()
    }

    @Test
    fun `load delivers complete replay before response and preserves unknown updates`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize("""{"loadSession":true}""")
        val loading = backgroundScope.async { h.client.loadSession("session", "/workspace") }
        val request = h.outgoing("session/load")
        assertEquals("session", request.obj("params").string("sessionId"))
        h.notification("""{"sessionUpdate":"future_update","opaque":{"value":1}}""")
        h.notification()
        h.reply(request, """{"modes":{"currentModeId":"ask"}}""")
        assertEquals("ask", loading.await().metadata.obj("modes").string("currentModeId"))
        assertEquals(
            listOf("future_update", "agent_message_chunk"),
            h.handler.updates.map { it.update.string("sessionUpdate") },
        )
        h.client.close()
    }

    @Test
    fun `authentication uses only an advertised method`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        assertFailsWith<IllegalArgumentException> { h.client.authenticate("unknown") }
        val authenticating = backgroundScope.async { h.client.authenticate("login") }
        val request = h.outgoing("authenticate")
        assertEquals("login", request.obj("params").string("methodId"))
        h.reply(request, "{}")
        authenticating.await()
        h.client.close()
    }

    @Test
    fun `out of order responses retain request correlation`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val first = backgroundScope.async { h.client.newSession("/first") }
        val request1 = h.outgoing("session/new")
        val second = backgroundScope.async { h.client.newSession("/second") }
        val request2 = h.outgoing("session/new")
        h.reply(request2, """{"sessionId":"second"}""")
        assertEquals("second", second.await().sessionId)
        assertFalse(first.isCompleted)
        h.reply(request1, """{"sessionId":"first"}""")
        assertEquals("first", first.await().sessionId)
        h.client.close()
    }

    @Test
    fun `remote errors retain code and data without exposing message in exception text`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val task = backgroundScope.async { assertFailsWith<AcpException.Remote> { h.client.newSession("/workspace") } }
        val request = h.outgoing("session/new")
        h.transport.input.send(
            (
                """{"jsonrpc":"2.0","id":${request.id()},"error":{"code":-32000,""" +
""""message":"private-token","data":{"retry":false}}}"""
            ),
        )
        val error = task.await()
        assertEquals(-32000, error.code)
        assertEquals("private-token", error.remoteMessage)
        assertFalse(error.toString().contains("private-token"))
        assertFalse(h.transport.isClosed)
        h.client.close()
    }

    @Test
    fun `relative working directory and foreign session are rejected`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        assertFailsWith<IllegalArgumentException> { h.client.newSession("relative") }
        assertFailsWith<IllegalArgumentException> { h.client.newSession("""\workspace""") }
        assertFailsWith<IllegalArgumentException> { h.client.prompt("foreign", "hello") }
        assertTrue(h.transport.output.tryReceive().isFailure)
        h.client.close()
    }
}
