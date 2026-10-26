package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CodexInventoryEndpointTest {
    @Test
    fun `accept and partial request reads are cancellable`() = runBlocking {
        CodexInventoryEndpoint().use { endpoint ->
            assertNull(withTimeoutOrNull(100) { endpoint.receive(Dispatchers.IO) })
        }
        CodexInventoryEndpoint().use { endpoint ->
            val uri = URI(endpoint.baseUrl)
            Socket(uri.host, uri.port).use { socket ->
                socket.getOutputStream().write("POST ".toByteArray())
                assertNull(withTimeoutOrNull(100) { endpoint.receive(Dispatchers.IO) })
            }
        }
    }

    @Test
    fun `captures one bounded response and never accepts credentials or ambiguous framing`() {
        val body = "{\"input\":[],\"tools\":[]}"
        assertEquals(
            json(
                "input" to kotlinx.serialization.json.JsonArray(emptyList()),
                "tools" to kotlinx.serialization.json.JsonArray(emptyList()),
            ),
            exchange("Content-Length: ${body.length}\r\n", body).getOrThrow(),
        )
        for (headers in listOf(
            "Authorization: Bearer fixture\r\nContent-Length: 2\r\n",
            "Content-Length: 2\r\nContent-Length: 2\r\n",
            "Content-Length: 1048577\r\n",
            "Transfer-Encoding: chunked\r\n",
            "Content-Encoding: gzip\r\nContent-Length: 2\r\n",
        )) {
            assertIs<EngineException>(exchange(headers, "{}").exceptionOrNull())
        }
    }

    private fun exchange(headers: String, body: String): Result<kotlinx.serialization.json.JsonObject> =
        CodexInventoryEndpoint().use { endpoint ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<Result<kotlinx.serialization.json.JsonObject>> {
                    runCatching { endpoint.capture() }
                }
                val uri = URI(endpoint.baseUrl)
                Socket(uri.host, uri.port).use { socket ->
                    socket.getOutputStream().write(
                        "POST ${uri.path}/responses HTTP/1.1\r\n$headers\r\n$body".toByteArray(),
                    )
                    received.get(5, TimeUnit.SECONDS)
                }
            } finally {
                executor.shutdownNow()
            }
        }
}
