package io.aequicor.heartbeat.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class NetworkResultTest {

    @Test
    fun success_is_returned_as_is() = runTest {
        assertEquals(Result.success(42), networkResult { 42 })
    }

    @Test
    fun non_2xx_response_becomes_http_error_without_url_or_body() = runTest {
        val client = HttpClient(MockEngine { respond("body-marker", HttpStatusCode.NotFound) }) { expectSuccess = true }

        val error = networkResult { client.get("https://test.local/items?q=query-marker") }.exceptionOrNull()

        assertIs<NetworkException.Http>(error)
        assertEquals(HttpStatusCode.NotFound, error.status)
        assertEquals("HTTP 404", error.message)
        assertNull(error.cause)
    }

    @Test
    fun timeouts_become_timeout_errors_without_the_url() = runTest {
        val request = networkResult { throw HttpRequestTimeoutException("https://test.local/?q=query-marker", 10) }
        val connect = networkResult { throw ConnectTimeoutException("connect") }

        val error = request.exceptionOrNull()
        assertIs<NetworkException.Timeout>(error)
        assertEquals("timeout (HttpRequestTimeoutException)", error.message)
        assertNull(error.cause)
        assertIs<NetworkException.Timeout>(connect.exceptionOrNull())
    }

    @Test
    fun other_io_failures_become_connectivity_errors_naming_the_origin() = runTest {
        val error = networkResult { throw IOException("reset") }.exceptionOrNull()

        assertIs<NetworkException.Connectivity>(error)
        assertEquals("IOException", error.origin)
    }

    @Test
    fun undecodable_body_becomes_invalid_response_without_its_content() = runTest {
        val error = networkResult { throw JsonConvertException("Illegal input: {\"secret\":\"body-marker\"}") }
            .exceptionOrNull()

        assertIs<NetworkException.InvalidResponse>(error)
        assertFalse("body-marker" in error.message.orEmpty())
        assertNull(error.cause)
    }

    @Test
    fun request_serialization_failure_is_a_bug_and_is_rethrown() = runTest {
        assertFailsWith<SerializationException> { networkResult { throw SerializationException("no serializer") } }
    }

    @Test
    fun cancellation_is_rethrown() = runTest {
        assertFailsWith<CancellationException> { networkResult { throw CancellationException("cancelled") } }
    }

    @Test
    fun bugs_are_not_wrapped() = runTest {
        assertFailsWith<IllegalStateException> { networkResult { error("bug") } }
    }
}
