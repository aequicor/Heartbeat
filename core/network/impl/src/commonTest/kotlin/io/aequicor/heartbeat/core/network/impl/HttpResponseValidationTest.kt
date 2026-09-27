package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.network.NetworkConfig
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class HttpResponseValidationTest {

    @Test
    fun malformed_response_content_type_becomes_a_safe_invalid_response() = runTest {
        withClient { client ->
            val error = networkResult { client.get("https://test.local/items").body<Item>() }.exceptionOrNull()

            assertIs<NetworkException.InvalidResponse>(error)
            assertNull(error.cause)
            assertFalse("private-header" in error.message.orEmpty())
            assertFalse("private-body" in error.message.orEmpty())
        }
    }

    @Test
    fun malformed_content_type_does_not_hide_an_http_failure() = runTest {
        withClient(status = HttpStatusCode.BadRequest) { client ->
            val error = networkResult { client.get("https://test.local/items").body<Item>() }.exceptionOrNull()

            assertIs<NetworkException.Http>(error)
            assertEquals(HttpStatusCode.BadRequest, error.status)
        }
    }

    @Test
    fun malformed_request_content_type_is_still_a_programming_error() = runTest {
        withClient { client ->
            assertFailsWith<BadContentTypeFormatException> {
                networkResult {
                    client.post("https://test.local/items") {
                        header(HttpHeaders.ContentType, "application-json")
                        setBody(Item("1"))
                    }
                }
            }
        }
    }

    @Test
    fun malformed_error_content_type_without_status_validation_is_invalid_response() = runTest {
        withClient(status = HttpStatusCode.BadRequest) { client ->
            val error = networkResult {
                client.get("https://test.local/items") { expectSuccess = false }.body<Item>()
            }.exceptionOrNull()

            assertIs<NetworkException.InvalidResponse>(error)
        }
    }

    @Test
    fun malformed_content_type_is_checked_after_the_last_retry() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts++
            respond("private-body", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "invalid"))
        }
        val client = createHttpClient(engine, NetworkConfig(maxRetries = 2), retryDelay = {})
        try {
            val error = networkResult { client.get("https://test.local/items").body<Item>() }.exceptionOrNull()

            assertIs<NetworkException.Http>(error)
            assertEquals(HttpStatusCode.ServiceUnavailable, error.status)
            assertEquals(3, attempts)
        } finally {
            client.close()
            engine.close()
        }
    }
    private suspend fun withClient(status: HttpStatusCode = HttpStatusCode.OK, block: suspend (HttpClient) -> Unit) {
        val engine = MockEngine {
            respond("private-body", status, headersOf(HttpHeaders.ContentType, "application-json-private-header"))
        }
        val client = createHttpClient(engine, NetworkConfig(maxRetries = 0))
        try {
            block(client)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Serializable
    private data class Item(val id: String)
}
