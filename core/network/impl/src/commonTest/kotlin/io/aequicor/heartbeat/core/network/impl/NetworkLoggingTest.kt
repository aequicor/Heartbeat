package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.network.NetworkConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkLoggingTest {

    private val messages = mutableListOf<String>()
    private val sink = LogSink { level, tag, _, message ->
        if (level == LogLevel.DEBUG && tag == NET_LOG_TAG) messages += message
    }

    @BeforeTest
    fun setUp() = Log.init(isDebug = true, sinks = listOf(sink))

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    @Test
    fun url_headers_do_not_leak_credentials_or_query_data_to_debug_logs() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/download") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = Headers.build {
                        append("LoCaTiOn", "https://test.local/file?sig=signature-marker#fragment-marker")
                        append("Content-Location", "/file?q=content-query-marker")
                        append("Link", "<https://test.local/next?cursor=cursor-marker>; rel=next")
                        append("Link", "<https://test.local/last?cursor=last-cursor-marker>; rel=last")
                        append("Refresh", "0; url=https://test.local/file?code=code-marker")
                    },
                )
            } else {
                respond("ok", headers = Headers.build { append(HttpHeaders.ContentType, "text/plain") })
            }
        }
        val client = createHttpClient(engine, NetworkConfig())
        try {
            val response = client.get("https://test.local/download") {
                header("rEfErEr", "https://name:credential-marker@test.local/search?q=referer-query-marker")
                header(HttpHeaders.Accept, "text/plain")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val logged = messages.joinToString("\n")
            listOf(
                "signature-marker",
                "fragment-marker",
                "content-query-marker",
                "cursor-marker",
                "last-cursor-marker",
                "code-marker",
                "credential-marker",
                "referer-query-marker",
            ).forEach { marker ->
                assertFalse(marker in logged, "$marker leaked: $logged")
            }
            listOf("location", "content-location", "referer", "link", "refresh").forEach { name ->
                assertTrue("$name=***" in logged.lowercase(), "$name was not redacted: $logged")
            }
            assertTrue("Accept=text/plain" in logged, logged)
            assertTrue("Content-Type=text/plain" in logged, logged)
        } finally {
            client.close()
            engine.close()
        }
    }
}
