package io.aequicor.heartbeat.feature.searchengine.impl

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import io.aequicor.heartbeat.feature.searchengine.impl.data.LocalSearchBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalSearchBridgeTest {
    @Test fun `MCP bridge authenticates and dispatches profile search`() {
        val profile = TestScope()
        var calls = 0
        val bridge = LocalSearchBridge(
            object : SearchEngine {
                override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
                    calls++
                    return listOf(SearchResult("https://example.com", "Example", "Snippet"))
                }
                override suspend fun fetch(url: String, native: EngineFeatures?) =
                    ResourceContent(url, "Example", "Page")
            },
            profile,
        )
        try {
            val endpoint = bridge.endpoint()
            val client = HttpClient.newHttpClient()
            fun post(path: String, body: String, token: String) = client.send(
                HttpRequest.newBuilder(URI("${endpoint.origin}$path"))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(403, post("/mcp", "{}", "wrong").statusCode())
            val list = post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", endpoint.token)
            assertEquals(200, list.statusCode())
            assertTrue(list.body().contains("web_search"))
            val call = post(
                "/mcp",
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{""" +
                    """"name":"web_search","arguments":{"query":"topic"}}}""",
                endpoint.token,
            )
            assertEquals(200, call.statusCode())
            assertTrue(call.body().contains("https://example.com"))
            assertEquals(1, calls)
        } finally {
            profile.close()
        }
    }

    @Test fun `execute endpoint returns tool result and clamps count`() {
        val profile = TestScope()
        var requested = 0
        val bridge = LocalSearchBridge(engine { _, count -> requested = count }, profile)
        try {
            val endpoint = bridge.endpoint()
            val response = post(
                endpoint.origin,
                "/execute",
                """{"name":"web_search","arguments":{"query":"q","count":500}}""",
                endpoint.token,
            )
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"success\":true"))
            assertEquals(20, requested)
        } finally {
            profile.close()
        }
    }

    @Test fun `concurrent requests are not serialized`() {
        val profile = TestScope()
        val started = CountDownLatch(2)
        val bridge = LocalSearchBridge(
            engine { _, _ ->
                started.countDown()
                // Both calls must be in flight at once for the latch to open.
                check(started.await(10, TimeUnit.SECONDS))
            },
            profile,
        )
        try {
            val endpoint = bridge.endpoint()
            val body = """{"name":"web_search","arguments":{"query":"q"}}"""
            val first = CompletableFuture.supplyAsync { post(endpoint.origin, "/execute", body, endpoint.token) }
            val second = CompletableFuture.supplyAsync { post(endpoint.origin, "/execute", body, endpoint.token) }
            assertTrue(first.get(20, TimeUnit.SECONDS).body().contains("\"success\":true"))
            assertTrue(second.get(20, TimeUnit.SECONDS).body().contains("\"success\":true"))
        } finally {
            profile.close()
        }
    }

    @Test fun `server stops when the profile closes and never restarts`() {
        val profile = TestScope()
        val bridge = LocalSearchBridge(engine { _, _ -> }, profile)
        val endpoint = bridge.endpoint()
        profile.close()
        assertFailsWith<IOException> { post(endpoint.origin, "/execute", "{}", endpoint.token) }
        assertFailsWith<IllegalStateException> { bridge.endpoint() }
    }
}

private fun engine(onSearch: (String, Int) -> Unit) = object : SearchEngine {
    override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
        onSearch(query, count)
        return listOf(SearchResult("https://example.com", "Example", "Snippet"))
    }
    override suspend fun fetch(url: String, native: EngineFeatures?) = ResourceContent(url, "Example", "Page")
}

private fun post(origin: String, path: String, body: String, token: String): HttpResponse<String> =
    HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("$origin$path"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

private class TestScope : ScopeHandle {
    private val callbacks = mutableListOf<() -> Unit>()
    override val name: String = "test"
    override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    override val savedState: ScopeSavedState get() = error("unused")
    override var isClosed: Boolean = false
    override fun onClose(action: () -> Unit): DisposableHandle {
        callbacks += action
        return DisposableHandle { callbacks.remove(action) }
    }
    fun close() {
        isClosed = true
        callbacks.toList().forEach { it() }
    }
}
