package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.network.NetworkConfig
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpRequestRetryTest {

    @Test
    fun consumed_channel_body_is_not_retried_after_server_error() = runTest {
        val exchange = execute(ByteReadChannel(PAYLOAD), Failure.SERVER)

        assertIs<NetworkException.Http>(exchange.result.exceptionOrNull())
        assertEquals(listOf(PAYLOAD), exchange.bodies)
    }

    @Test
    fun consumed_channel_body_is_not_retried_after_connection_failure() = runTest {
        val exchange = execute(ByteReadChannel(PAYLOAD), Failure.CONNECTION)

        assertIs<NetworkException.Connectivity>(exchange.result.exceptionOrNull())
        assertEquals(listOf(PAYLOAD), exchange.bodies)
    }

    @Test
    fun wrapped_channel_body_is_not_retried() = runTest {
        val channel = ByteReadChannel(PAYLOAD)
        val content = object : OutgoingContent.ReadChannelContent() {
            override fun readFrom(): ByteReadChannel = channel
        }
        val exchange = execute(WrappedContent(WrappedContent(content)), Failure.SERVER)

        assertIs<NetworkException.Http>(exchange.result.exceptionOrNull())
        assertEquals(listOf(PAYLOAD), exchange.bodies)
    }

    @Test
    fun write_channel_body_is_not_assumed_replayable() = runTest {
        val content = object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeStringUtf8(PAYLOAD)
            }
        }
        val exchange = execute(content, Failure.SERVER)

        assertIs<NetworkException.Http>(exchange.result.exceptionOrNull())
        assertEquals(listOf(PAYLOAD), exchange.bodies)
    }

    @Test
    fun byte_array_body_is_replayed_after_server_error() = runTest {
        val exchange = execute(PAYLOAD.encodeToByteArray(), Failure.SERVER)

        assertEquals(HttpStatusCode.OK, exchange.result.getOrThrow())
        assertEquals(listOf(PAYLOAD, PAYLOAD), exchange.bodies)
    }

    @Test
    fun byte_array_body_is_replayed_after_connection_failure() = runTest {
        val exchange = execute(PAYLOAD.encodeToByteArray(), Failure.CONNECTION)

        assertEquals(HttpStatusCode.OK, exchange.result.getOrThrow())
        assertEquals(listOf(PAYLOAD, PAYLOAD), exchange.bodies)
    }

    @Test
    fun transformed_string_body_is_replayed() = runTest {
        val exchange = execute(PAYLOAD, Failure.SERVER)

        assertEquals(HttpStatusCode.OK, exchange.result.getOrThrow())
        assertEquals(listOf(PAYLOAD, PAYLOAD), exchange.bodies)
    }

    @Test
    fun wrapped_byte_array_body_is_replayed() = runTest {
        val content = WrappedContent(WrappedContent(TextContent(PAYLOAD, ContentType.Text.Plain)))
        val exchange = execute(content, Failure.SERVER)

        assertEquals(HttpStatusCode.OK, exchange.result.getOrThrow())
        assertEquals(listOf(PAYLOAD, PAYLOAD), exchange.bodies)
    }

    @Test
    fun request_without_body_is_still_retried() = runTest {
        val exchange = execute(null, Failure.SERVER, HttpMethod.Get)

        assertEquals(HttpStatusCode.OK, exchange.result.getOrThrow())
        assertEquals(listOf("", ""), exchange.bodies)
    }

    private suspend fun execute(body: Any?, failure: Failure, method: HttpMethod = HttpMethod.Put): Exchange {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            if (bodies.size == 1) {
                when (failure) {
                    Failure.SERVER -> respond("", HttpStatusCode.ServiceUnavailable)
                    Failure.CONNECTION -> throw IOException("Connection reset after receiving the body")
                }
            } else {
                respond("ok")
            }
        }
        val client = createHttpClient(engine, NetworkConfig(maxRetries = 1), retryDelay = {})
        return try {
            val result = networkResult {
                client.request("https://test.local/items/1") {
                    this.method = method
                    if (body != null) setBody(body)
                }.status
            }
            Exchange(result, bodies.toList())
        } finally {
            client.close()
            engine.close()
        }
    }

    private class WrappedContent(delegate: OutgoingContent) : OutgoingContent.ContentWrapper(delegate) {
        override fun copy(delegate: OutgoingContent): OutgoingContent.ContentWrapper = WrappedContent(delegate)
    }

    private data class Exchange(val result: Result<HttpStatusCode>, val bodies: List<String>)

    private enum class Failure {
        SERVER,
        CONNECTION,
    }

    private companion object {
        const val PAYLOAD = "payload"
    }
}
