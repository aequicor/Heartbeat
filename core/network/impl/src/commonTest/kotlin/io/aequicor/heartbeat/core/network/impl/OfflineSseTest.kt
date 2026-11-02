package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.aequicor.heartbeat.core.network.OfflineHttpSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.serverSentEventsSession
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSseTest {
    @Test
    fun `real Ktor parser decodes raw SSE fields and does not emit an unterminated event`() = runTest {
        val f = fixture(": ignored\nevent: chunk\nid: 7\ndata: REA\ndata: DY\n\ndata: NEXT\n\ndata: unfinished")
        try {
            val stream = f.client.serverSentEventsSession(URL)
            val events = stream.incoming.toList()
            assertEquals(listOf("REA\r\nDY", "NEXT"), events.map { it.data })
            assertEquals("chunk", events.first().event)
            assertEquals(listOf("7", "7"), events.map { it.id })
            assertTrue(stream.coroutineContext.job.isCompleted)
            assertTrue(f.owner.progress().isComplete)
        } finally {
            f.close()
        }
    }

    @Test
    fun `abandoned parser and its noncancellable child cleanup are awaited by the owner`() = runTest {
        val f = fixture("data: READY\n\n")
        val stream = f.client.serverSentEventsSession(URL)
        val job = stream.coroutineContext.job
        assertTrue(job in f.owner.client.engine.coroutineContext.job.children)
        val cleanupStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        CoroutineScope(stream.coroutineContext).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    release.await()
                }
            }
        }
        runCurrent()
        val shutdown = async { f.close() }
        try {
            cleanupStarted.await()
            assertFalse(shutdown.isCompleted)
        } finally {
            release.complete(Unit)
        }
        shutdown.await()
        assertTrue(job.isCompleted)
        assertTrue(f.owner.client.engine.coroutineContext.job.isCompleted)
    }

    @Test
    fun `cancelling the actual engine call interrupts acquisition without closing the session`() = runTest {
        val f = fixture(response = OfflineHttpResponse.AwaitCancellation)
        val call = async { f.owner.client.get(URL) }
        try {
            runCurrent()
            assertEquals(1, f.owner.progress().received)
            call.cancel()
            runCurrent()
            assertTrue(call.isCompleted)
            assertFailsWith<CancellationException> { call.await() }
            assertTrue(f.owner.client.coroutineContext.job.isActive)
            assertTrue(f.owner.client.engine.coroutineContext.job.isActive)
        } finally {
            f.close()
        }
    }

    @Test
    fun `owner shutdown interrupts SSE acquisition before headers arrive`() = runTest {
        val f = fixture(response = OfflineHttpResponse.AwaitCancellation)
        val call = async { f.client.serverSentEventsSession(URL) }
        runCurrent()
        assertEquals(1, f.owner.progress().received)
        assertFalse(call.isCompleted)
        f.close()
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(f.client.coroutineContext.job.isCompleted)
        assertTrue(f.owner.client.engine.coroutineContext.job.isCompleted)
    }

    @Test
    fun `cancelling collection closes the real parser`() = runTest {
        val f = fixture("data: READY\n\n")
        val stream = f.client.serverSentEventsSession(URL)
        val entered = CompletableDeferred<Unit>()
        val call = async {
            stream.incoming.collect {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            entered.await()
            call.cancel()
            assertFailsWith<CancellationException> { call.await() }
            assertTrue(stream.coroutineContext.job.isCompleted)
        } finally {
            f.close()
        }
    }

    @Test
    fun `owner shutdown cancels a parser waiting to reconnect without waiting for its timer`() = runTest {
        val f = fixture("data: READY\n\n", attempts = 1, reconnect = 1000.seconds)
        val stream = f.client.serverSentEventsSession(URL)
        val call = async { stream.incoming.toList() }
        runCurrent()
        assertEquals(1, f.owner.progress().received)
        assertFalse(call.isCompleted)
        f.close()
        call.join()
        assertTrue(stream.coroutineContext.job.isCompleted)
        assertEquals(1, f.owner.progress().received)
    }

    @Test
    fun `an unscripted reconnect permanently fails matching and cannot use another transport`() = runTest {
        val f = fixture("data: READY\n\n", attempts = 1)
        try {
            val stream = f.client.serverSentEventsSession(URL)
            assertFailsWith<Exception> { stream.incoming.toList() }
            assertEquals(2, f.owner.progress().received)
            assertFalse(f.owner.progress().isComplete)
            assertTrue(stream.coroutineContext.job.isCompleted)
        } finally {
            f.close()
        }
    }

    @Test
    fun `cancellation after parser acquisition before delivery cannot strand its job`() = runTest {
        var reference: Fixture? = null
        var consumer: Job? = null
        val acquired = CompletableDeferred<Job>()
        val f = fixture("data: READY\n\n", delivery = { response ->
            val current = requireNotNull(reference)
            val parser = current.owner.client.engine.coroutineContext.job.children.single {
                it !== current.owner.client.coroutineContext.job && it !== current.client.coroutineContext.job
            }
            acquired.complete(parser)
            response.coroutineContext.job.cancel()
            requireNotNull(consumer).cancel()
        })
        reference = f
        try {
            val call = async {
                consumer = currentCoroutineContext().job
                f.client.serverSentEventsSession(URL)
            }
            val parser = acquired.await()
            assertFailsWith<CancellationException> { call.await() }
            runCurrent()
            assertTrue(parser.isCompleted)
        } finally {
            f.close()
        }
    }

    @Test
    fun `invalid SSE status and media type do not allocate a parser`() = runTest {
        val replies = listOf(
            OfflineHttpResponse.Reply(503, mapOf("Content-Type" to "text/event-stream"), ""),
            OfflineHttpResponse.Reply(headers = mapOf("Content-Type" to "application/json"), body = "{}"),
        )
        for (reply in replies) {
            val f = fixture(response = reply)
            try {
                assertFailsWith<SSEClientException> { f.client.serverSentEventsSession(URL) }
                runCurrent()
                assertTrue(f.owner.progress().isComplete)
                assertTrue(
                    f.owner.client.engine.coroutineContext.job.children.all {
                        it === f.owner.client.coroutineContext.job || it === f.client.coroutineContext.job
                    },
                )
            } finally {
                f.close()
            }
        }
    }

    private suspend fun TestScope.fixture(
        body: String = "",
        response: OfflineHttpResponse = OfflineHttpResponse.Reply(
            headers = mapOf("Content-Type" to "text/event-stream"),
            body = body,
        ),
        attempts: Int = 0,
        reconnect: Duration = Duration.ZERO,
        delivery: ((HttpResponse) -> Unit)? = null,
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = SocketFreeHttpClients(object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        })
        val owner = factory.open(listOf(OfflineHttpExchange("GET", URL, response = response)))
        val client = owner.client.config {
            install(SSE) {
                maxReconnectionAttempts = attempts
                reconnectionTime = reconnect
            }
            if (delivery != null) install(createClientPlugin("CancelOfflineDelivery") { onResponse { delivery(it) } })
        }
        return Fixture(owner, client)
    }

    private class Fixture(val owner: OfflineHttpSession, val client: HttpClient) {
        suspend fun close() = withContext(NonCancellable) {
            try {
                client.close()
            } finally {
                owner.closeAndAwait()
                client.coroutineContext.job.join()
            }
        }
    }
}

private const val URL = "https://offline.invalid/stream"
