package io.aequicor.heartbeat.core.network.impl

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.network.NetworkConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class OkHttpRetryTest {

    @Test
    fun post_and_patch_are_not_replayed_after_408() = runTest {
        for (method in listOf(HttpMethod.Post, HttpMethod.Patch)) {
            assertSingleRequest(method, status = 408)
        }
    }

    @Test
    fun post_and_patch_are_not_replayed_after_503_with_immediate_retry_after() = runTest {
        for (method in listOf(HttpMethod.Post, HttpMethod.Patch)) {
            assertSingleRequest(method, status = 503, retryAfter = "0")
        }
    }

    @Test
    fun get_respects_the_ktor_retry_budget_including_zero() = runTest {
        for (maxRetries in listOf(0, 2)) {
            TestServer(status = 503, retryAfter = "0").use { server ->
                withClient(maxRetries) { client ->
                    val response = client.request(server.url) { expectSuccess = false }

                    assertEquals(503, response.status.value)
                    assertEquals(maxRetries + 1, server.requests.size)
                    assertEquals("0", response.headers[HttpHeaders.RetryAfter])
                    assertEquals(listOf("first", "second"), response.headers.getAll("X-Repeated"))
                    assertEquals(RESPONSE_BODY, response.bodyAsText())
                }
            }
        }
    }

    @Test
    fun status_421_and_a_genuine_599_response_are_preserved() = runTest {
        for (status in listOf(421, 599)) {
            TestServer(status).use { server ->
                withClient(maxRetries = 0) { client ->
                    val response = client.request(server.url) { expectSuccess = false }

                    assertEquals(status, response.status.value)
                    assertEquals(1, server.requests.size)
                    assertEquals(RESPONSE_BODY, response.bodyAsText())
                }
            }
        }
    }

    @Test
    fun streamed_put_is_not_replayed_after_503() = runTest {
        TestServer(status = 503, retryAfter = "0").use { server ->
            withClient(maxRetries = 2) { client ->
                val response = client.request(server.url) {
                    method = HttpMethod.Put
                    setBody(ByteReadChannel(REQUEST_BODY))
                    expectSuccess = false
                }

                assertEquals(503, response.status.value)
                assertEquals(listOf(ReceivedRequest("PUT", REQUEST_BODY)), server.requests)
                assertEquals(RESPONSE_BODY, response.bodyAsText())
            }
        }
    }

    private suspend fun assertSingleRequest(method: HttpMethod, status: Int, retryAfter: String? = null) {
        TestServer(status, retryAfter).use { server ->
            withClient(maxRetries = 2) { client ->
                val response = client.request(server.url) {
                    this.method = method
                    setBody(REQUEST_BODY)
                    expectSuccess = false
                }

                assertEquals(status, response.status.value)
                assertEquals(listOf(ReceivedRequest(method.value, REQUEST_BODY)), server.requests)
                assertEquals(retryAfter, response.headers[HttpHeaders.RetryAfter])
                assertEquals(RESPONSE_BODY, response.bodyAsText())
            }
        }
    }

    private suspend fun withClient(maxRetries: Int, block: suspend (HttpClient) -> Unit) {
        val engine = createOkHttpEngine()
        val client = createHttpClient(engine, NetworkConfig(maxRetries = maxRetries), retryDelay = {})
        try {
            block(client)
        } finally {
            client.close()
            engine.close()
        }
    }

    private class TestServer(status: Int, retryAfter: String? = null) : AutoCloseable {
        val requests = CopyOnWriteArrayList<ReceivedRequest>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    val body = exchange.requestBody.use { it.readBytes().decodeToString() }
                    requests += ReceivedRequest(exchange.requestMethod, body)
                    retryAfter?.let { exchange.responseHeaders.add(HttpHeaders.RetryAfter, it) }
                    exchange.responseHeaders.add("X-Repeated", "first")
                    exchange.responseHeaders.add("X-Repeated", "second")
                    val response = RESPONSE_BODY.toByteArray()
                    exchange.sendResponseHeaders(status, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
            }
            start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}/generate"

        override fun close() = server.stop(0)
    }

    private data class ReceivedRequest(val method: String, val body: String)

    private companion object {
        const val REQUEST_BODY = "payload"
        const val RESPONSE_BODY = "original response body"
    }
}
