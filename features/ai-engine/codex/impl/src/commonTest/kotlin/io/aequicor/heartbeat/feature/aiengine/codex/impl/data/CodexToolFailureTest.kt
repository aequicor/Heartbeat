package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexToolFailureTest {
    @Test
    fun `provider cancellation answers the tool even when the parent turn is still active`() = runTest {
        val fixture = Fixture(this, failingSearch(CancellationException("Provider cancelled")))
        requestTool(fixture)
        runCurrent()
        val response = fixture.wire.written.single { it["id"] == JsonPrimitive(500) }.obj("result")
        assertTrue(response.toString().contains("Cancelled"))
        fixture.runtime.close()
    }

    @Test
    fun `fatal tool errors propagate without being reported as cancellation`() {
        val fatal = AssertionError("Fatal provider error")
        val propagated = assertFailsWith<AssertionError> {
            runTest {
                val fixture = Fixture(this, failingSearch(fatal))
                requestTool(fixture)
                runCurrent()
                assertFalse(fixture.wire.written.any { it["id"] == JsonPrimitive(500) })
                fixture.runtime.close()
            }
        }
        assertSame(fatal, propagated)
    }

    private suspend fun requestTool(fixture: Fixture) {
        val session = fixture.open()
        session.feature(SendsPrompts).send(PromptRequest(RequestId("prompt"), listOf(ContentPart.Text("Search"))))
        fixture.event(
            "item/tool/call",
            "turnId" to "native-turn".json(),
            "tool" to "web_search".json(),
            "arguments" to json("query" to "topic".json()),
            id = JsonPrimitive(500),
        )
    }

    private fun failingSearch(error: Throwable): SearchEngine = object : SearchEngine {
        override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> =
            throw error
        override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent = throw error
    }
}
