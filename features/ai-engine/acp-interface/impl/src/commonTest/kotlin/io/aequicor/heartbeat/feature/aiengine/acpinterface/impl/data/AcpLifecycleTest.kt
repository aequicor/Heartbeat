package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.di.DefaultAcpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcpLifecycleTest {
    @Test
    fun `EOF fails pending calls and closes transport`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val pending = backgroundScope.async { assertFailsWith<Exception> { h.client.newSession("/workspace") } }
        h.outgoing("session/new")
        h.transport.input.close()
        pending.await()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `malformed message fails all pending requests`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val pending = backgroundScope.async { assertFailsWith<Exception> { h.client.newSession("/workspace") } }
        h.outgoing("session/new")
        h.transport.input.send("not JSON")
        pending.await()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `response with both error and result closes connection`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        val pending = backgroundScope.async { assertFailsWith<Exception> { h.client.newSession("/workspace") } }
        val request = h.outgoing("session/new")
        h.transport.input.send(
            """{"jsonrpc":"2.0","id":${request.id()},"result":{},"error":{"code":1,"message":"invalid"}}""",
        )
        pending.await()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `close is idempotent and unblocks a running prompt`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val pending = backgroundScope.async { assertFailsWith<Exception> { h.client.prompt("session", "hello") } }
        h.outgoing("session/prompt")
        h.client.close()
        h.client.close()
        pending.await()
        assertTrue(h.transport.isClosed)
    }

    @Test
    fun `owner cancellation closes transport even with no pending operation`() = runTest {
        val owner = Job()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport()
        DefaultAcpClientFactory(TestDispatchers(dispatcher))
            .connect(transport, CoroutineScope(owner + dispatcher), RecordingHandler())
        owner.cancelAndJoin()
        assertTrue(transport.isClosed)
    }

    @Test
    fun `already cancelled owner cannot leak a transport`() = runTest {
        val owner = Job().also { it.cancel() }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport()
        DefaultAcpClientFactory(TestDispatchers(dispatcher))
            .connect(transport, CoroutineScope(owner + dispatcher), RecordingHandler())
        owner.join()
        // The reader enters its cleanup even when its parent was already cancelled.
        testScheduler.runCurrent()
        assertTrue(transport.isClosed)
    }

    @Test
    fun `update consumer failure closes connection instead of losing streaming silently`() = runTest {
        val transport = FakeTransport()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = DefaultAcpClientFactory(TestDispatchers(dispatcher)).connect(
            transport,
            backgroundScope,
            object : AcpClientHandler {
                override suspend fun onSessionUpdate(update: AcpSessionUpdate) = error("consumer failed")
            },
        )
        transport.input.send("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{}}}""")
        testScheduler.runCurrent()
        assertTrue(transport.isClosed)
        client.close()
    }
}
