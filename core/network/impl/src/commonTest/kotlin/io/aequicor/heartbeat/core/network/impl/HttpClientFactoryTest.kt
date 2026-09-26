package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.network.NetworkConfig
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpClientFactoryTest {

    private val records = mutableListOf<Record>()
    private val delays = mutableListOf<Long>()
    private var calls = 0

    private val sink = LogSink { level, tag, error, message -> records += Record(level, tag, error, message) }

    @BeforeTest
    fun setUp() = Log.init(isDebug = true, sinks = listOf(sink))

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    private fun client(
        config: NetworkConfig = NetworkConfig(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = createHttpClient(
        engine = MockEngine { request ->
            calls++
            handler(request)
        },
        config = config,
        retryDelay = { delays += it },
    )

    private val netMessages get() = records.filter { it.tag == NET_LOG_TAG }.map { it.message }

    @Test
    fun json_body_is_decoded_ignoring_unknown_keys() = runTest {
        val client = client { respond("""{"id":"1","extra":true}""", HttpStatusCode.OK, JsonHeaders) }

        val item: ItemDto = client.get("https://test.local/items/1").body()

        assertEquals(ItemDto("1"), item)
    }

    @Test
    fun non_2xx_fails_the_call_with_its_status() = runTest {
        val client = client { respond("", HttpStatusCode.Unauthorized) }

        val error = networkResult { client.get("https://test.local/me") }.exceptionOrNull()

        assertIs<NetworkException.Http>(error)
        assertEquals(HttpStatusCode.Unauthorized, error.status)
        assertEquals(1, calls)
    }

    @Test
    fun idempotent_request_is_retried_on_server_error_with_backoff() = runTest {
        val client = client(NetworkConfig(maxRetries = 2)) {
            if (calls < 3) respond("", HttpStatusCode.ServiceUnavailable) else respond("ok")
        }

        client.get("https://test.local/items")

        assertEquals(3, calls)
        assertEquals(2, delays.size)
        assertTrue(delays[1] >= 2_000, "exponential backoff, got $delays")
        assertEquals(2, netMessages.count { it.startsWith("retry #") })
    }

    @Test
    fun retries_give_up_after_max_retries() = runTest {
        val client = client(NetworkConfig(maxRetries = 1)) { respond("", HttpStatusCode.BadGateway) }

        val error = networkResult { client.get("https://test.local/items") }.exceptionOrNull()

        assertIs<NetworkException.Http>(error)
        assertEquals(2, calls)
    }

    @Test
    fun post_is_never_retried() = runTest {
        val client = client { respond("", HttpStatusCode.ServiceUnavailable) }

        networkResult { client.post("https://test.local/generate") }

        assertEquals(1, calls)
    }

    @Test
    fun idempotent_request_is_retried_on_connection_failure() = runTest {
        val client = client { if (calls == 1) throw IOException("connection reset") else respond("ok") }

        client.get("https://test.local/items")

        assertEquals(2, calls)
    }

    @Test
    fun timeouts_are_not_retried_and_are_logged_once() = runTest {
        val client = client { throw ConnectTimeoutException("connect timeout") }

        val error = networkResult { client.get("https://test.local/items") }.exceptionOrNull()

        assertIs<NetworkException.Timeout>(error)
        assertEquals(1, calls)
        assertEquals(1, records.count { it.level == LogLevel.WARNING })
    }

    @Test
    fun malformed_json_becomes_invalid_response() = runTest {
        val client = client { respond("""{"id":""", HttpStatusCode.OK, JsonHeaders) }

        val error = networkResult { client.get("https://test.local/items/1").body<ItemDto>() }.exceptionOrNull()

        assertIs<NetworkException.InvalidResponse>(error)
    }

    @Test
    fun every_attempt_is_logged_with_method_url_status_and_duration() = runTest {
        val client = client { if (calls == 1) respond("", HttpStatusCode.InternalServerError) else respond("ok") }

        client.get("https://test.local:8443/items?query=private&page=2")

        val summaries = records.filter { it.level >= LogLevel.INFO && it.message.contains(" -> ") }
        assertEquals(listOf(LogLevel.WARNING, LogLevel.INFO), summaries.map { it.level })
        assertTrue(
            Regex("""^GET https://test\.local:8443/items\?page=\*\*\*&query=\*\*\* -> 200 \(\d+ ms\)$""")
                .matches(summaries.last().message),
            summaries.last().message,
        )
        assertFalse(netMessages.any { "private" in it })
    }

    @Test
    fun failures_are_logged_by_exception_class() = runTest {
        val client = client(NetworkConfig(maxRetries = 0)) { throw IOException("unreachable") }

        networkResult { client.get("https://test.local/items") }

        val failure = records.single { it.level == LogLevel.WARNING }
        assertTrue(failure.message.startsWith("GET https://test.local/items failed: IOException ("), failure.message)
    }

    @Test
    fun urls_and_bodies_do_not_leak_through_exceptions() = runTest {
        val url = "https://test.local/items?q=query-marker"
        val failures = listOf(
            client { respond("body-marker", HttpStatusCode.BadRequest) },
            client { throw ConnectTimeoutException("Connect timeout has expired [url=$url]") },
            client { respond("""{"id":"body-marker""", HttpStatusCode.OK, JsonHeaders) },
        ).map { client -> networkResult { client.get(url).body<ItemDto>() }.exceptionOrNull() }

        val texts = records.flatMap { listOf(it.message) + it.error.causeMessages() } +
            failures.flatMap { it.causeMessages() }
        assertEquals(3, failures.count { it is NetworkException })
        listOf("query-marker", "body-marker").forEach { marker ->
            assertFalse(texts.any { marker in it }, "$marker leaked: $texts")
        }
    }

    @Test
    fun request_timeout_is_logged_once() = runTest {
        val client = client(NetworkConfig(requestTimeoutMillis = 50)) {
            awaitCancellation()
        }

        val error = networkResult { client.get("https://test.local/slow") }.exceptionOrNull()

        assertIs<NetworkException.Timeout>(error)
        assertEquals(1, calls)
        val timeout = records.single { it.level == LogLevel.WARNING }
        assertEquals("GET https://test.local/slow timed out", timeout.message)
        assertTrue(netMessages.any { it.startsWith("GET https://test.local/slow interrupted") }, "$netMessages")
    }

    @Test
    fun sensitive_headers_and_bodies_are_not_logged() = runTest {
        val client = client {
            respond("body-marker", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "sid=cookie-marker"))
        }

        client.get("https://test.local/me") {
            header(HttpHeaders.Cookie, "sid=cookie-marker")
            header("X-Api-Key", "key-marker")
            header(HttpHeaders.Accept, "application/json")
        }

        val logged = netMessages.joinToString("\n")
        listOf("cookie-marker", "key-marker", "body-marker").forEach { marker ->
            assertFalse(marker in logged, "$marker leaked: $logged")
        }
        assertTrue("Accept=application/json" in logged, logged)
        assertTrue("X-Api-Key=***" in logged, logged)
    }

    private fun Throwable?.causeMessages(): List<String> =
        generateSequence(this) { it.cause }.mapNotNull { it.message }.toList()

    @Serializable
    private data class ItemDto(val id: String)

    private data class Record(val level: LogLevel, val tag: String, val error: Throwable?, val message: String)

    private companion object {
        val JsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
