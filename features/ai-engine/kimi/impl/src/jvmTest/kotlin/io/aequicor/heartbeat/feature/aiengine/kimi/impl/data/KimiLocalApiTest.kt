package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.NetworkException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KimiLocalApiTest {
    @Test
    fun `relative Kimi home follows the CLI working directory`() {
        val home = File(System.getProperty("java.io.tmpdir"), "kimi-home")
        val cwd = File(home, "workspace")
        assertEquals(File(cwd, "relative"), kimiLocalHome("relative", home.path, cwd.path))
        assertEquals(File(home, ".kimi-code"), kimiLocalHome(null, home.path, cwd.path))
        assertEquals(home, kimiLocalHome(home.path, "ignored", cwd.path))
    }

    @Test
    fun `oversized local response is rejected during bounded reading`() = runTest {
        val client = HttpClient(MockEngine { respond(" ".repeat(1_048_577), HttpStatusCode.OK) })
        try {
            assertFailsWith<IllegalArgumentException> {
                readKimiLocalJson(client, 12345, "local-server-bearer", "/api/v1/models")
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `quota request uses loopback and local bearer without touching login`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("127.0.0.1", request.url.host)
                assertEquals(12345, request.url.port)
                assertEquals("/api/v1/oauth/usage", request.url.encodedPath)
                assertEquals("Bearer local-server-bearer", request.headers[HttpHeaders.Authorization])
                respond(
                    """{"data":{"kind":"ok","quota":{"usages":{}}}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        try {
            assertTrue("data" in readKimiLocalJson(client, 12345, "local-server-bearer", "/api/v1/oauth/usage"))
            assertFailsWith<IllegalArgumentException> {
                readKimiLocalJson(client, 12345, "local-server-bearer", "/api/v1/oauth/login")
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `HTTP failure never carries response body or bearer in domain error`() = runTest {
        val client = HttpClient(MockEngine { respond("sensitive-body", HttpStatusCode.Unauthorized) })
        try {
            val error = assertFailsWith<NetworkException.Http> {
                readKimiLocalJson(client, 12345, "local-server-bearer", "/api/v1/oauth/usage")
            }
            assertEquals("HTTP 401", error.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun `short lived server is released after read and cancellation`() = runTest {
        val completed = ProbeProcess()
        assertEquals("ok", withKimiWebProcess(dispatchers, { completed }) { _, _ -> "ok" })
        assertTrue(completed.isDestroyed.get())
        val cancelled = ProbeProcess()
        val ready = CompletableDeferred<Unit>()
        val task = async {
            withKimiWebProcess(dispatchers, { cancelled }) { _, _ ->
                ready.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        ready.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(cancelled.isDestroyed.get())
    }

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }
}
