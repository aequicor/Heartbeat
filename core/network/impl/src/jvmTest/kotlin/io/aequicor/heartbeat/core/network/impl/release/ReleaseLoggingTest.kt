package io.aequicor.heartbeat.core.network.impl.release

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.network.NetworkConfig
import io.aequicor.heartbeat.core.network.impl.createHttpClient
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReleaseLoggingTest {
    @Test
    fun `retries and redirects expose no paths or secrets and the consumer logs one warning`() = runTest {
        val records = java.util.concurrent.CopyOnWriteArrayList<Pair<LogLevel, String>>()
        val sink = LogSink { level, _, error, message -> records += level to "$message ${error?.message.orEmpty()}" }
        Log.init(isDebug = true, sinks = listOf(sink))
        var attempt = 0
        val engine = MockEngine {
            attempt++
            when (attempt) {
                1 -> respond("secret-body", HttpStatusCode.BadGateway)

                2 -> respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(
                        HttpHeaders.Location,
                        "https://objects.githubusercontent.com/private-path?key=secret-query",
                    ),
                )

                else -> respond("secret-body", HttpStatusCode.Forbidden)
            }
        }
        val client = createHttpClient(engine, NetworkConfig(maxRetries = 1), retryDelay = {})
        try {
            try {
                HttpReleaseSource(client, dispatchers()).document(
                    "https://github.com/private-path?key=secret-query",
                    setOf("github.com", "objects.githubusercontent.com"),
                )
                error("expected failure")
            } catch (e: ReleaseException) {
                assertEquals(403, e.status)
                Log.tag("Consumer").w(e) { "release failed" }
            }
            assertEquals(3, attempt)
            assertEquals(1, records.count { it.first == LogLevel.WARNING })
            listOf("private-path", "secret-query", "secret-body").forEach { marker ->
                assertFalse(records.any { marker in it.second }, "leaked $marker")
            }
        } finally {
            client.close()
            engine.close()
            Log.init(isDebug = false)
        }
    }
}
