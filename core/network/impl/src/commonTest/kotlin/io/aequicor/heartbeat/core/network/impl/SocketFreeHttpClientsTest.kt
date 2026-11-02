package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SocketFreeHttpClientsTest {
    private val messages = mutableListOf<String>()

    @BeforeTest
    fun setUp() = Log.init(
        isDebug = true,
        sinks = listOf(
            LogSink { _, _, error, text ->
                messages += "$text ${error?.message.orEmpty()}"
            },
        ),
    )

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    @Test
    fun `exact synthetic request receives fixture bytes without a platform engine`() = runTest {
        val fixture = exchange().copy(headers = mapOf("X-Key" to "never-expose-key"), body = "never-expose-body")
        val session = clients().open(listOf(fixture))
        try {
            assertIsOffline(session.client.engine)
            val response = session.client.post(fixture.url) {
                header("x-key", "never-expose-key")
                setBody("never-expose-body")
            }
            assertEquals("data: READY\n\n", response.bodyAsText())
            assertTrue(session.progress().isComplete)
            assertFalse(messages.any { "never-expose" in it })
        } finally {
            session.closeAndAwait()
        }
        assertTrue(session.client.coroutineContext.job.isCompleted)
        assertTrue(session.client.engine.coroutineContext.job.isCompleted)
    }

    @Test
    fun `method URL header and body mismatches permanently fail the script`() = runTest {
        val variants = listOf(
            exchange().copy(method = "GET"),
            exchange().copy(url = "$URL/other"),
            exchange().copy(headers = mapOf("X-Key" to "required")),
            exchange().copy(body = "required"),
        )
        for (fixture in variants) {
            val session = clients().open(listOf(fixture, exchange()))
            try {
                val failure = assertFailsWith<IllegalStateException> { session.client.post(URL) }
                assertEquals("Offline HTTP script mismatch", failure.message)
                assertTrue(
                    generateSequence<Throwable>(failure) { it.cause }.take(8)
                        .all { it.message == "Offline HTTP script mismatch" },
                )
                assertFailsWith<IllegalStateException> { session.client.post(URL) }
                assertFalse(session.progress().isMatched)
                assertFalse(session.progress().isComplete)
                assertEquals(2, session.progress().received)
            } finally {
                session.closeAndAwait()
            }
        }
    }

    @Test
    fun `unused and extra exchanges never report complete`() = runTest {
        val session = clients().open(listOf(exchange()))
        try {
            assertFalse(session.progress().isComplete)
            session.client.post(URL)
            assertTrue(session.progress().isComplete)
            assertFailsWith<IllegalStateException> { session.client.post(URL) }
            assertFalse(session.progress().isComplete)
            assertEquals(2, session.progress().received)
        } finally {
            session.closeAndAwait()
        }
    }

    @Test
    fun `redirects and server errors never trigger another request`() = runTest {
        for (status in listOf(302, 503)) {
            val fixture = exchange().copy(
                method = "GET",
                response = OfflineHttpResponse.Reply(status, mapOf("Location" to "$URL/fallback"), ""),
            )
            val session = clients().open(listOf(fixture))
            try {
                assertEquals(status, session.client.get(URL).status.value)
                assertTrue(session.progress().isComplete)
            } finally {
                session.closeAndAwait()
            }
        }
    }

    @Test
    fun `session closure cancels a pending response and joins both owned jobs`() = runTest {
        val session = clients().open(listOf(exchange().copy(response = OfflineHttpResponse.AwaitCancellation)))
        val call = async { session.client.post(URL) }
        runCurrent()
        assertEquals(1, session.progress().received)
        assertFalse(call.isCompleted)
        session.closeAndAwait()
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(session.client.coroutineContext.job.isCompleted)
        assertTrue(session.client.engine.coroutineContext.job.isCompleted)
        session.closeAndAwait()
    }

    @Test
    fun `cancelled caller still awaits transport cleanup`() = runTest {
        val session = clients().open(listOf(exchange().copy(response = OfflineHttpResponse.AwaitCancellation)))
        val call = async {
            try {
                session.client.post(URL)
            } finally {
                session.closeAndAwait()
            }
        }
        runCurrent()
        call.cancel()
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(session.client.coroutineContext.job.isCompleted)
        assertTrue(session.client.engine.coroutineContext.job.isCompleted)
    }

    @Test
    fun `script and header mutations after acquisition cannot alter the owned snapshot`() = runTest {
        val requestHeaders = mutableMapOf("X-Key" to "fixed")
        val responseHeaders = mutableMapOf("X-Result" to "fixed")
        val script = mutableListOf(
            exchange().copy(
                headers = requestHeaders,
                response = OfflineHttpResponse.Reply(headers = responseHeaders, body = "fixed"),
            ),
        )
        val session = clients().open(script)
        script.clear()
        requestHeaders.clear()
        responseHeaders.clear()
        try {
            val response = session.client.post(URL) { header("X-Key", "fixed") }
            assertEquals("fixed", response.headers["X-Result"])
            assertEquals("fixed", response.bodyAsText())
            assertTrue(session.progress().isComplete)
        } finally {
            session.closeAndAwait()
        }
    }

    @Test
    fun `invalid or oversized scripts fail before allocation with safe errors`() = runTest {
        val invalid = listOf(
            emptyList(), List(17) { exchange() },
            listOf(exchange().copy(method = "post")),
            listOf(exchange().copy(url = "relative/path")),
            listOf(exchange().copy(url = "https://secret:never-expose@offline.invalid/")),
            listOf(exchange().copy(url = "$URL#never-expose")),
            listOf(exchange().copy(url = "https://[never-expose/")),
            listOf(exchange().copy(body = "x".repeat(65_537))),
            listOf(exchange().copy(body = "Ж".repeat(40_000))),
            listOf(exchange().copy(headers = (0..32).associate { "x-$it" to "v" })),
            listOf(exchange().copy(headers = mapOf("X-A" to "v", "x-a" to "v"))),
            listOf(exchange().copy(headers = mapOf("" to "value"))),
            listOf(exchange().copy(headers = mapOf("X-\u007f" to "value"))),
            listOf(exchange().copy(headers = mapOf("X-Ж" to "value"))),
            listOf(exchange().copy(headers = mapOf("X-A" to "never-expose\r\nvalue"))),
            listOf(exchange().copy(response = OfflineHttpResponse.Reply(status = 600, body = ""))),
            List(16) { exchange().copy(body = "x".repeat(65_536)) },
        )
        for ((index, script) in invalid.withIndex()) {
            val failure = assertFailsWith<IllegalArgumentException>("Invalid script $index") { clients().open(script) }
            assertEquals("Invalid offline HTTP script", failure.message)
            assertNull(failure.cause)
        }
        assertFalse(messages.any { "never-expose" in it })
    }

    @Test
    fun `streaming request bodies are refused without reading their producer`() = runTest {
        var reads = 0
        val content = object : OutgoingContent.ReadChannelContent() {
            override fun readFrom(): ByteReadChannel {
                reads++
                return ByteReadChannel("never-expose")
            }
        }
        val session = clients().open(listOf(exchange()))
        try {
            assertFailsWith<IllegalStateException> { session.client.post(URL) { setBody(content) } }
            assertEquals(0, reads)
            assertFalse(session.progress().isMatched)
        } finally {
            session.closeAndAwait()
        }
    }

    @Test
    fun `throwing byte extraction and excessive wrappers permanently fail matching`() = runTest {
        val broken = object : OutgoingContent.ByteArrayContent() {
            override val contentLength = 1L
            override fun bytes(): ByteArray = error("never-expose")
        }
        var wrapped: OutgoingContent = object : OutgoingContent.NoContent() {}
        repeat(9) { wrapped = Wrapper(wrapped) }
        for (content in listOf(broken, wrapped)) {
            val session = clients().open(listOf(exchange()))
            try {
                val failure = assertFailsWith<IllegalStateException> { session.client.post(URL) { setBody(content) } }
                assertEquals("Offline HTTP script mismatch", failure.message)
                assertFalse(session.progress().isComplete)
                assertFalse(session.progress().isMatched)
            } finally {
                session.closeAndAwait()
            }
        }
        assertFalse(messages.any { "never-expose" in it })
    }

    @Test
    fun `body extraction cancellation is propagated and cannot confirm the script`() = runTest {
        val original = CancellationException("synthetic extraction cancellation")
        val content = object : OutgoingContent.ByteArrayContent() {
            override val contentLength = 1L
            override fun bytes(): ByteArray = throw original
        }
        val session = clients().open(listOf(exchange()))
        try {
            val cancelled = assertFailsWith<CancellationException> { session.client.post(URL) { setBody(content) } }
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.take(8).any { it === original })
            assertFalse(session.progress().isMatched)
        } finally {
            session.closeAndAwait()
        }
    }

    @Test
    fun `maximum body size is accepted and larger actual bodies are refused`() = runTest {
        val body = "x".repeat(65_536)
        val fixture = exchange().copy(body = body, response = OfflineHttpResponse.Reply(body = body))
        val session = clients().open(listOf(fixture))
        try {
            assertEquals(body, session.client.post(URL) { setBody(body) }.bodyAsText())
            assertTrue(session.progress().isComplete)
        } finally {
            session.closeAndAwait()
        }
        val refused = clients().open(listOf(exchange()))
        try {
            assertFailsWith<IllegalStateException> { refused.client.post(URL) { setBody(body + "x") } }
            assertFalse(refused.progress().isMatched)
        } finally {
            refused.closeAndAwait()
        }
    }

    @Test
    fun `effective content headers are matched with the same precedence as a platform engine`() = runTest {
        val content = object : OutgoingContent.ByteArrayContent() {
            override val contentType = ContentType.Application.Json
            override val contentLength = 2L
            override val headers = Headers.build { append("X-Body", "from-body") }
            override fun bytes() = "{}".encodeToByteArray()
        }
        val correct = mapOf("Content-Type" to "application/json", "Content-Length" to "2", "X-Body" to "from-body")
        val variants = listOf(correct, correct + ("Content-Type" to "text/plain"), correct + ("Content-Length" to "3"))
        for ((index, headers) in variants.withIndex()) {
            val session = clients().open(listOf(exchange().copy(headers = headers, body = "{}")))
            try {
                if (index == 0) {
                    session.client.post(URL) {
                        header("Content-Type", "text/plain")
                        setBody(content)
                    }
                    assertTrue(session.progress().isComplete)
                } else {
                    assertFailsWith<IllegalStateException> { session.client.post(URL) { setBody(content) } }
                    assertFalse(session.progress().isComplete)
                }
            } finally {
                session.closeAndAwait()
            }
        }
    }

    @Test
    fun `HTTP loopback and IPv6 fixture origins use the same socket-free engine`() = runTest {
        for (url in listOf("http://127.0.0.1:11434/api/chat", "http://[::1]:11434/api/chat")) {
            val session = clients().open(listOf(exchange().copy(url = url)))
            try {
                assertIsOffline(session.client.engine)
                assertEquals("data: READY\n\n", session.client.post(url).bodyAsText())
                assertTrue(session.progress().isComplete)
            } finally {
                session.closeAndAwait()
            }
        }
    }

    private fun TestScope.clients(): SocketFreeHttpClients {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SocketFreeHttpClients(object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        })
    }

    private fun exchange() = OfflineHttpExchange(
        "POST",
        URL,
        response = OfflineHttpResponse.Reply(
            headers = mapOf("Content-Type" to "text/event-stream"),
            body = "data: READY\n\n",
        ),
    )

    private fun assertIsOffline(engine: Any) = assertTrue(engine is ScriptedOfflineEngine)

    private class Wrapper(delegate: OutgoingContent) : OutgoingContent.ContentWrapper(delegate) {
        override fun copy(delegate: OutgoingContent): OutgoingContent.ContentWrapper = Wrapper(delegate)
    }
}

private const val URL = "https://never-expose-host.invalid:1234/never-expose-path"
