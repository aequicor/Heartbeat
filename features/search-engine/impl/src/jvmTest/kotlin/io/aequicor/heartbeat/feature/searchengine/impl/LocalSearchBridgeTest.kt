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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

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
