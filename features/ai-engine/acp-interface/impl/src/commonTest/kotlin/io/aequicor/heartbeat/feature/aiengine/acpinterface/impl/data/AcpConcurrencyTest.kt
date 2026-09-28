package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
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

class AcpConcurrencyTest {
    @Test
    fun `cancel waits for prompt submission but never for prompt completion`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.transport.beforeSend = {
            val frame = acpJson.parseToJsonElement(it) as JsonObject
            if (frame.string("method") == "session/prompt") {
                started.complete(Unit)
                release.await()
            }
        }
        val prompt = backgroundScope.async { h.client.prompt("session", "hello") }
        started.await()
        val cancel = backgroundScope.async { h.client.cancel("session") }
        testScheduler.runCurrent()
        assertFalse(cancel.isCompleted)
        assertTrue(h.transport.output.tryReceive().isFailure)
        release.complete(Unit)
        val request = h.outgoing("session/prompt")
        val notification = h.outgoing("session/cancel")
        assertFalse("id" in notification)
        cancel.await()
        assertFalse(prompt.isCompleted)
        h.reply(request, """{"stopReason":"cancelled"}""")
        prompt.await()
        h.client.close()
    }

    @Test
    fun `permission transport failure closes connection without uncaught background exception`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.transport.beforeSend = { error("sensitive transport details") }
        h.permission()
        testScheduler.runCurrent()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `permissions arriving after cancel are cancelled without asking the handler`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        var requests = 0
        h.handler.permission = {
            requests++
            AcpPermissionOutcome.Selected("allow")
        }
        val prompt = backgroundScope.async { h.client.prompt("session", "hello") }
        val request = h.outgoing("session/prompt")
        h.client.cancel("session")
        h.outgoing("session/cancel")
        h.permission()
        val decision = acpJson.parseToJsonElement(h.transport.output.receive()) as JsonObject
        assertEquals("cancelled", decision.obj("result").obj("outcome").string("outcome"))
        assertEquals(0, requests)
        h.reply(request, """{"stopReason":"cancelled"}""")
        prompt.await()
        h.client.close()
    }

    @Test
    fun `write failure unblocks all pending calls`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val first = backgroundScope.async {
            assertFailsWith<AcpException.Disconnected> { h.client.newSession("/workspace") }
        }
        h.outgoing("session/new")
        h.transport.beforeSend = { error("pipe failed") }
        assertFailsWith<AcpException.Disconnected> { h.client.newSession("/another") }
        first.await()
        assertTrue(h.transport.isClosed)
    }
}
