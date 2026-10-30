package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorBootstrapTransportTest {
    @Test
    fun manifestReadStopsAtLimit() {
        val engine = MockEngine { respond("response exceeding the limit") }
        KtorBootstrapTransport(HttpClient(engine)).use { transport ->
            assertFailsWith<IllegalArgumentException> { runBlocking { transport.getBytes(URL, 3) } }
        }
    }

    @Test
    fun failedHttpResponseDoesNotOpenAnArtifactSink() {
        val engine = MockEngine { respond("not found", HttpStatusCode.NotFound) }
        KtorBootstrapTransport(HttpClient(engine)).use { transport ->
            var isStarted = false
            val result = runBlocking {
                transport.download(URL, 0, { isStarted = true }, { error("Unexpected body") })
            }
            assertEquals(404, result.status)
            assertFalse(isStarted)
        }
    }

    @Test
    fun resumeRequiresMatchingContentRangeBeforeWriting() {
        val engine = MockEngine { respond("tail", HttpStatusCode.PartialContent) }
        KtorBootstrapTransport(HttpClient(engine)).use { transport ->
            var isStarted = false
            assertFailsWith<IllegalArgumentException> {
                runBlocking { transport.download(URL, 3, { isStarted = true }, { error("Unexpected body") }) }
            }
            assertFalse(isStarted)
        }
    }

    @Test
    fun validResumeStreamsOnlyTheTail() {
        val engine = MockEngine { request ->
            assertEquals("bytes=3-", request.headers[HttpHeaders.Range])
            respond("def", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 3-5/6"))
        }
        KtorBootstrapTransport(HttpClient(engine)).use { transport ->
            val body = ArrayList<Byte>()
            val result = runBlocking {
                transport.download(URL, 3, { assertTrue(it) }, { body.addAll(it.toList()) })
            }
            assertTrue(result.isResumed)
            assertEquals("def", body.toByteArray().decodeToString())
        }
    }

    companion object {
        private const val URL = "https://bootstrap.invalid/artifact"
    }
}
