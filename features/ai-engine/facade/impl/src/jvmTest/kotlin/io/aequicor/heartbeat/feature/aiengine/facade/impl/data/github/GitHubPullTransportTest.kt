package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubPullTransportTest {
    private val configuration = GitHubPullConfiguration("owner/repo", "fixture-token", "selected-user")
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    @Test
    fun `credentials and payload only reach the fixed GitHub origin`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            assertEquals("Bearer fixture-token", request.headers[HttpHeaders.Authorization])
            assertEquals("2026-03-10", request.headers["X-GitHub-Api-Version"])
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine) { install(HttpTimeout) }.use { client ->
            val transport = HttpGitHubPullTransport(client, dispatchers)
            transport.call(configuration, "PATCH", "/repos/owner/repo/pulls/1", buildJsonObject { put("body", "Text") })
            transport.call(configuration, "GET", "/repos/owner/repo", null)
            assertFailsWith<IllegalArgumentException> {
                transport.call(configuration, "POST", "https://other.invalid", null)
            }
            assertFailsWith<IllegalArgumentException> {
                transport.call(configuration, "POST", "/repos/other/repo/pulls", null)
            }
        }
        assertEquals(
            listOf("https://api.github.com/repos/owner/repo/pulls/1", "https://api.github.com/repos/owner/repo"),
            requests,
        )
    }

    @Test
    fun `redirects are refused before sending credentials or body to another destination`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                "",
                HttpStatusCode.TemporaryRedirect,
                headersOf(HttpHeaders.Location, "https://other.invalid/steal"),
            )
        }
        HttpClient(engine) { install(HttpTimeout) }.use { client ->
            assertFailsWith<IllegalStateException> {
                HttpGitHubPullTransport(client, dispatchers).call(
                    configuration,
                    "POST",
                    "/repos/owner/repo/pulls",
                    buildJsonObject { put("body", "private") },
                )
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `failed writes are never automatically retried even if the parent client enables retries`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("untrusted error", HttpStatusCode.ServiceUnavailable)
        }
        HttpClient(engine) {
            install(HttpTimeout)
            install(HttpRequestRetry) {
                maxRetries = 3
                retryIf { _, _ -> true }
                delayMillis { 0 }
            }
        }.use { client ->
            assertFailsWith<IllegalStateException> {
                HttpGitHubPullTransport(client, dispatchers).call(configuration, "POST", "/graphql", buildJsonObject {})
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `oversized and invalid response bodies fail without exposing contents`() = runTest {
        for (body in listOf("x".repeat(1024 * 1024 + 1), "PRIVATE invalid JSON")) {
            val engine = MockEngine { respond(body, HttpStatusCode.OK) }
            HttpClient(engine) { install(HttpTimeout) }.use { client ->
                val failure = assertFailsWith<IllegalStateException> {
                    HttpGitHubPullTransport(client, dispatchers).call(configuration, "GET", "/repos/owner/repo", null)
                }
                kotlin.test.assertFalse(failure.message.orEmpty().contains("PRIVATE"))
            }
        }
    }
}
