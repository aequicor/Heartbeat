package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AcpSessionListTest {
    @Test
    fun `session list parses entries and the next cursor`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val listing = backgroundScope.async { h.client.listSessions("/workspace") }
        val request = h.outgoing("session/list")
        h.reply(
            request,
            """{"sessions":[{"sessionId":"one"},{"sessionId":"two","title":"Second"}],"nextCursor":"page-2"}""",
        )
        val page = listing.await()
        assertEquals(listOf("one", "two"), page.sessions.map { it.sessionId })
        assertEquals("page-2", page.nextCursor)
        h.client.close()
    }

    @Test
    fun `malformed session list is a protocol violation not an empty page`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val listing = backgroundScope.async {
            assertFailsWith<AcpException.Protocol> { h.client.listSessions() }
        }
        val request = h.outgoing("session/list")
        h.reply(request, """{"sessions":[{"title":"no id"}]}""")
        listing.await()
    }

    @Test
    fun `missing sessions field is a protocol violation`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val listing = backgroundScope.async {
            assertFailsWith<AcpException.Protocol> { h.client.listSessions() }
        }
        val request = h.outgoing("session/list")
        h.reply(request, """{}""")
        listing.await()
    }

    @Test
    fun `a null next cursor marks the final session page`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val listing = backgroundScope.async { h.client.listSessions() }
        h.reply(h.outgoing("session/list"), """{"sessions":[],"nextCursor":null}""")
        assertNull(listing.await().nextCursor)
        h.client.close()
    }

    @Test
    fun `a non-string next cursor is a protocol violation`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val listing = backgroundScope.async {
            assertFailsWith<AcpException.Protocol> { h.client.listSessions() }
        }
        h.reply(h.outgoing("session/list"), """{"sessions":[],"nextCursor":42}""")
        listing.await()
        h.client.close()
    }

    @Test
    fun `close session releases the agent-side session`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val closing = backgroundScope.async { h.client.closeSession("session") }
        val request = h.outgoing("session/close")
        assertEquals("session", request.obj("params").string("sessionId"))
        h.reply(request, """{}""")
        closing.await()
        // The session no longer belongs to the connection.
        assertFailsWith<IllegalArgumentException> { h.client.closeSession("session") }
        h.client.close()
    }

    @Test
    fun `delete session removes the owned native session`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize("""{"sessionCapabilities":{"delete":{}}}""")
        h.session()
        val deleting = backgroundScope.async { h.client.deleteSession("session") }
        val request = h.outgoing("session/delete")
        assertEquals("session", request.obj("params").string("sessionId"))
        h.reply(request, """{}""")
        deleting.await()
        assertFailsWith<IllegalArgumentException> { h.client.deleteSession("session") }
        h.client.close()
    }

    @Test
    fun `delete refuses an unadvertised capability without sending a request`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        assertFailsWith<IllegalStateException> { h.client.deleteSession("session") }
        kotlin.test.assertTrue(h.transport.output.tryReceive().isFailure)
        h.client.close()
    }

    @Test
    fun `delete refuses a session with an active turn`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize("""{"sessionCapabilities":{"delete":{}}}""")
        h.session()
        val prompt = backgroundScope.async { h.client.prompt("session", "hello") }
        val request = h.outgoing("session/prompt")
        assertFailsWith<IllegalStateException> { h.client.deleteSession("session") }
        kotlin.test.assertTrue(h.transport.output.tryReceive().isFailure)
        h.reply(request, """{"stopReason":"end_turn"}""")
        prompt.await()
        h.client.close()
    }
}
