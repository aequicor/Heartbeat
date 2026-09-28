package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpPromptTest {
    @Test
    fun `prompt streams updates and resolves permissions before finishing`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        h.handler.permission = { AcpPermissionOutcome.Selected(it.options.first().optionId) }
        val prompt = backgroundScope.async { h.client.prompt("session", "hello\nworld") }
        val request = h.outgoing("session/prompt")
        h.notification()
        h.permission()
        val decision = acpJson.parseToJsonElement(h.transport.output.receive()) as JsonObject
        assertEquals(90, decision.number("id"))
        assertEquals("allow", decision.obj("result").obj("outcome").string("optionId"))
        h.reply(request, """{"stopReason":"end_turn"}""")
        assertEquals("end_turn", prompt.await().stopReason)
        assertEquals(1, h.handler.updates.size)
        h.client.close()
    }

    @Test
    fun `cancel is a notification and pending permissions become cancelled`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val permissionStarted = CompletableDeferred<Unit>()
        h.handler.permission = {
            permissionStarted.complete(Unit)
            CompletableDeferred<AcpPermissionOutcome>().await()
        }
        val prompt = backgroundScope.async { h.client.prompt("session", "hello") }
        val request = h.outgoing("session/prompt")
        h.permission()
        permissionStarted.await()
        h.client.cancel("session")
        val messages = List(2) { acpJson.parseToJsonElement(h.transport.output.receive()) as JsonObject }
        val cancel = messages.single { "method" in it }
        assertEquals("session/cancel", cancel.string("method"))
        assertFalse("id" in cancel)
        assertEquals("cancelled", messages.single { "result" in it }.obj("result").obj("outcome").string("outcome"))
        assertFalse(prompt.isCompleted)
        h.notification()
        h.reply(request, """{"stopReason":"cancelled"}""")
        assertEquals("cancelled", prompt.await().stopReason)
        assertEquals(1, h.handler.updates.size)
        h.client.close()
    }

    @Test
    fun `abandoning prompt await keeps native turn busy until final response`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val prompt = backgroundScope.async { h.client.prompt("session", "hello") }
        val request = h.outgoing("session/prompt")
        prompt.cancel()
        prompt.join()
        assertFailsWith<IllegalStateException> { h.client.prompt("session", "duplicate") }
        assertTrue(h.transport.output.tryReceive().isFailure)
        h.reply(request, """{"stopReason":"end_turn"}""")
        // An explicit round trip establishes that the previous response has been processed.
        val auth = backgroundScope.async { h.client.authenticate("login") }
        h.reply(h.outgoing("authenticate"), "{}")
        auth.await()
        val next = backgroundScope.async { h.client.prompt("session", "next") }
        h.reply(h.outgoing("session/prompt"), """{"stopReason":"end_turn"}""")
        assertEquals("end_turn", next.await().stopReason)
        h.client.close()
    }

    @Test
    fun `invalid permission choice never grants access`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.handler.permission = { AcpPermissionOutcome.Selected("not-offered") }
        h.permission()
        val decision = acpJson.parseToJsonElement(h.transport.output.receive()) as JsonObject
        assertEquals("cancelled", decision.obj("result").obj("outcome").string("outcome"))
        h.client.close()
    }

    @Test
    fun `unknown agent requests get method not found while unknown notifications are ignored`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.transport.input.send("""{"jsonrpc":"2.0","method":"future/notice","params":{}}""")
        h.transport.input.send("""{"jsonrpc":"2.0","id":"agent-1","method":"fs/read_text_file","params":{}}""")
        val response = acpJson.parseToJsonElement(h.transport.output.receive()) as JsonObject
        assertEquals("agent-1", response.string("id"))
        assertEquals(-32601, response.obj("error").number("code"))
        h.client.close()
    }
}
