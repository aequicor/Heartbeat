package io.aequicor.heartbeat.feature.searchengine.impl

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeEndpoint
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeGuard
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import io.aequicor.heartbeat.feature.searchengine.impl.data.LocalSearchBridge
import io.aequicor.heartbeat.feature.searchengine.impl.data.executeScopedSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScopedSearchBridgeTest {
    @Test
    fun `approval can outlast the search IO budget without consuming it`() = runTest {
        val approval = CompletableDeferred<Unit>()
        var calls = 0
        val access = Job()
        try {
            val pending = async {
                executeScopedSearch(
                    SearchBridgeGuard { _, _, operation ->
                        approval.await()
                        operation()
                    },
                    "web_search",
                    SEARCH_ARGS,
                    access,
                    timeoutMillis = 60_000,
                ) {
                    calls++
                    AgentToolResult("Completed")
                }
            }
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertFalse(pending.isCompleted)
            assertEquals(0, calls)
            approval.complete(Unit)
            assertEquals(AgentToolResult("Completed"), pending.await())
            assertEquals(1, calls)
        } finally {
            access.cancel()
        }
    }

    @Test
    fun `search IO remains bounded after a long authorization wait`() = runTest {
        val approval = CompletableDeferred<Unit>()
        var isCancelled = false
        val access = Job()
        try {
            val pending = async {
                executeScopedSearch(
                    SearchBridgeGuard { _, _, operation ->
                        approval.await()
                        operation()
                    },
                    "web_search",
                    SEARCH_ARGS,
                    access,
                    timeoutMillis = 60_000,
                ) {
                    try {
                        awaitCancellation()
                    } finally {
                        isCancelled = true
                    }
                }
            }
            runCurrent()
            advanceTimeBy(120_000)
            approval.complete(Unit)
            runCurrent()
            advanceTimeBy(59_999)
            runCurrent()
            assertFalse(pending.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(AgentToolResult("Timeout", isError = true), pending.await())
            assertTrue(isCancelled)
        } finally {
            access.cancel()
        }
    }

    @Test
    fun `wrong scoped token and a denied guard never reach search`() {
        val fixture = ScopedFixture()
        var guards = 0
        val access = fixture.bridge.attach(
            SearchBridgeGuard { name, arguments, _ ->
                guards++
                assertEquals("web_search", name)
                assertEquals(SEARCH_ARGS, arguments)
                AgentToolResult("Denied", isError = true)
            },
        )
        try {
            assertFalse(access.endpoint.token == fixture.bridge.endpoint().token)
            assertEquals(403, post(access.endpoint.copy(token = "wrong"), SEARCH_CALL).statusCode())
            assertEquals(0, guards)
            val denied = post(access.endpoint, SEARCH_CALL)
            assertEquals(200, denied.statusCode())
            assertTrue(denied.body().contains("\"success\":false"))
            assertEquals(1, guards)
            assertEquals(0, fixture.calls.get())
        } finally {
            access.close()
            fixture.close()
        }
    }

    @Test
    fun `scoped requests preserve full arguments and authorize before both search and fetch`() {
        val fixture = ScopedFixture()
        val unrelated = fixture.bridge.attach(
            object : EngineFeatures {
                override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                    FeatureAccess.Unsupported
            },
        )
        val expected = listOf("web_search" to SEARCH_ARGS, "web_fetch" to FETCH_ARGS)
        val guarded = mutableListOf<Pair<String, JsonObject>>()
        val access = fixture.bridge.attach(
            SearchBridgeGuard { name, arguments, operation ->
                guarded += name to arguments
                assertEquals(guarded.size - 1, fixture.calls.get())
                val result = operation()
                assertEquals(guarded.size, fixture.calls.get())
                result.copy(text = "reviewed: " + result.text)
            },
        )
        try {
            assertTrue(post(access.endpoint, SEARCH_CALL).body().contains("reviewed:"))
            assertTrue(post(access.endpoint, FETCH_CALL).body().contains("reviewed:"))
            assertEquals(expected, guarded)
            assertEquals(listOf("topic" to 7), fixture.queries)
            assertEquals(listOf("https://example.test/page"), fixture.urls)
        } finally {
            access.close()
            unrelated.detach()
            fixture.close()
        }
    }

    @Test
    fun `closing an access revokes its token without revoking a different generation`() {
        val fixture = ScopedFixture()
        val guard = SearchBridgeGuard { _, _, operation -> operation() }
        val previous = fixture.bridge.attach(guard)
        val current = fixture.bridge.attach(guard)
        try {
            previous.close()
            previous.close()
            assertEquals(403, post(previous.endpoint, SEARCH_CALL).statusCode())
            assertTrue(post(current.endpoint, SEARCH_CALL).body().contains("\"success\":true"))
            assertEquals(1, fixture.calls.get())
        } finally {
            current.close()
            fixture.close()
        }
    }

    @Test
    fun `closing an access cancels a pending approval without executing search`() {
        val fixture = ScopedFixture()
        val started = CountDownLatch(1)
        val access = fixture.bridge.attach(
            SearchBridgeGuard { _, _, _ ->
                started.countDown()
                awaitCancellation()
            },
        )
        try {
            val pending = CompletableFuture.supplyAsync { post(access.endpoint, SEARCH_CALL) }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            access.close()
            assertEquals(503, pending.get(10, TimeUnit.SECONDS).statusCode())
            assertEquals(0, fixture.calls.get())
        } finally {
            access.close()
            fixture.close()
        }
    }

    @Test
    fun `one scoped authorization cannot execute an operation twice`() {
        val fixture = ScopedFixture()
        val access = fixture.bridge.attach(
            SearchBridgeGuard { _, _, operation ->
                val result = operation()
                assertFailsWith<IllegalStateException> { operation() }
                result
            },
        )
        try {
            assertTrue(post(access.endpoint, SEARCH_CALL).body().contains("\"success\":true"))
            assertEquals(1, fixture.calls.get())
        } finally {
            access.close()
            fixture.close()
        }
    }
}

private class ScopedFixture : AutoCloseable {
    val calls = AtomicInteger()
    val queries = mutableListOf<Pair<String, Int>>()
    val urls = mutableListOf<String>()
    private val profile = ScopedProfile()
    val bridge = LocalSearchBridge(
        object : SearchEngine {
            override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
                calls.incrementAndGet()
                queries += query to count
                assertNull(native)
                return emptyList()
            }
            override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent {
                calls.incrementAndGet()
                urls += url
                assertNull(native)
                return ResourceContent(url, "title", "content")
            }
        },
        profile,
    )
    override fun close() = profile.close()
}

private class ScopedProfile : ScopeHandle {
    private val callbacks = mutableListOf<() -> Unit>()
    override val name = "scoped-search"
    override val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val savedState: ScopeSavedState get() = error("unused")
    override var isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle {
        callbacks += action
        return DisposableHandle { callbacks.remove(action) }
    }
    fun close() {
        isClosed = true
        callbacks.toList().forEach { it() }
        coroutineScope.cancel()
    }
}

private fun post(endpoint: SearchBridgeEndpoint, body: String): HttpResponse<String> = HttpClient.newHttpClient().send(
    HttpRequest.newBuilder(URI("${endpoint.origin}/execute"))
        .header("Authorization", "Bearer ${endpoint.token}")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
    HttpResponse.BodyHandlers.ofString(),
)

private val SEARCH_ARGS = Json.parseToJsonElement("""{"query":"topic","count":7,"extra":"unchanged"}""") as JsonObject
private val FETCH_ARGS = Json.parseToJsonElement("""{"url":"https://example.test/page"}""") as JsonObject
private val SEARCH_CALL = """{"name":"web_search","arguments":$SEARCH_ARGS}"""
private val FETCH_CALL = """{"name":"web_fetch","arguments":$FETCH_ARGS}"""
