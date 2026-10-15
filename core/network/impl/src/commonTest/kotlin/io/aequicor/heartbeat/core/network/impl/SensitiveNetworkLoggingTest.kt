package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.network.NetworkConfig
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.SensitiveNetworkRequest
import io.aequicor.heartbeat.core.network.networkResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SensitiveNetworkLoggingTest {
    private val messages = mutableListOf<String>()
    private val sink = LogSink { _, tag, error, message ->
        if (tag == NET_LOG_TAG) messages += "$message ${error?.message.orEmpty()}"
    }

    @BeforeTest
    fun setUp() = Log.init(isDebug = true, sinks = listOf(sink))

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    @Test
    fun redirects_and_retries_keep_metadata_private_but_status_visible() = runTest {
        var finalCalls = 0
        val engine = MockEngine { request ->
            assertTrue(request.attributes.contains(SensitiveNetworkRequest))
            if (request.url.encodedPath.endsWith("/first")) {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://secret-host.invalid/secret-path/final?secret-name=value"),
                )
            } else {
                finalCalls++
                respond(
                    "secret-body",
                    if (finalCalls == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                    Headers.build {
                        append(HttpHeaders.ContentType, "text/plain")
                        append("X-Private-Data", "secret-response-header")
                    },
                )
            }
        }
        val client = createHttpClient(engine, NetworkConfig(maxRetries = 1), retryDelay = {})
        try {
            val response = client.get("https://secret-host.invalid/secret-path/first?secret-name=value") {
                attributes.put(SensitiveNetworkRequest, Unit)
                header("X-Private-Data", "secret-request-header")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, finalCalls)
            val logged = messages.joinToString("\n")
            assertFalse("secret-" in logged, logged)
            assertFalse("X-Private-Data" in logged, logged)
            assertTrue("retry #1 GET ***" in logged, logged)
            assertTrue("GET *** -> 200" in logged, logged)
            assertTrue("headers: ***" in logged, logged)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test
    fun request_timeout_keeps_url_private() = runTest {
        val engine = MockEngine { awaitCancellation() }
        val client = createHttpClient(engine, NetworkConfig(requestTimeoutMillis = 20, maxRetries = 0))
        try {
            val error = networkResult {
                client.get("https://secret-host.invalid/secret-path?secret-name=value") {
                    attributes.put(SensitiveNetworkRequest, Unit)
                }
            }.exceptionOrNull()
            assertIs<NetworkException.Timeout>(error)
            val logged = messages.joinToString("\n")
            assertFalse("secret-" in logged, logged)
            assertTrue("GET *** timed out" in logged, logged)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test
    fun transport_failure_keeps_exception_metadata_private() = runTest {
        val engine = MockEngine { throw IOException("secret-error contains secret-path") }
        val client = createHttpClient(engine, NetworkConfig(maxRetries = 0))
        try {
            val error = networkResult {
                client.get("https://secret-host.invalid/secret-path?secret-name=value") {
                    attributes.put(SensitiveNetworkRequest, Unit)
                }
            }.exceptionOrNull()
            assertIs<NetworkException.Connectivity>(error)
            val logged = messages.joinToString("\n")
            assertFalse("secret-" in logged, logged)
            assertTrue("GET *** failed: IOException" in logged, logged)
        } finally {
            client.close()
            engine.close()
        }
    }
}
