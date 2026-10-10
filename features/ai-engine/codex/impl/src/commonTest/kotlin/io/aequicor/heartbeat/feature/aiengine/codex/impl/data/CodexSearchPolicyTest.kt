package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexSearchPolicyTest {
    @Test
    fun `hosted refusal prevents both providers without requesting native approval`() = runTest {
        val search = Search()
        val tools = Tools().apply { verdict = NativeVerdict.Deny("Policy denied") }
        val fixture = Fixture(this, search, tools = tools)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        for (name in listOf("web_search", "web_fetch")) {
            fixture.call(name)
            runCurrent()
            assertEquals(toolResult(false, "Policy denied"), fixture.response())
        }
        assertEquals(0, search.calls)
        assertEquals(0, tools.completed.size)
        assertIs<ActiveSessionState.Running>(session.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `search hook receives original arguments identity and complete provider result`() = runTest {
        val tools = Tools().apply { note = "Hook context" }
        val fixture = Fixture(this, Search(), tools = tools)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        fixture.call("web_search")
        runCurrent()
        val context = checkNotNull(tools.context)
        assertEquals(session.ref, context.session)
        assertEquals(turn, context.turn)
        assertEquals(Prompt.id, context.request)
        assertEquals(fixture.target, context.target)
        assertEquals(arguments, tools.arguments)
        assertTrue(checkNotNull(context.lifetime).isActive)
        assertSame(context, tools.completed.single().first)
        assertTrue(tools.completed.single().second.text.contains("https://example.com"))
        val response = fixture.response()
        assertEquals(JsonPrimitive(true), response["success"])
        assertTrue(response.output().startsWith("Hook context\n\n"))
        assertTrue(response.output().contains("https://example.com"))
        fixture.runtime.close()
    }

    @Test
    fun `search waiting for hook permission is withdrawn when its turn ends`() = runTest {
        val tools = Tools().apply { ask = true }
        val search = Search()
        val fixture = Fixture(this, search, tools = tools)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        fixture.call("web_search")
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        assertEquals(0, search.calls)
        session.feature(CancelsTurns).cancel(turn)
        runCurrent()
        assertEquals(toolFailureResult("Cancelled"), fixture.response())
        assertEquals(0, search.calls)
        assertFalse(checkNotNull(tools.context?.lifetime).isActive)
        assertEquals(0, tools.completed.size)
        fixture.runtime.close()
    }

    @Test
    fun `after hook note is bounded and does not replace a failed fetch result`() = runTest {
        val tools = Tools().apply { note = "n".repeat(4_000) }
        val fixture = Fixture(this, Search().apply { failFetch = true }, tools = tools)
        fixture.open().feature(SendsPrompts).send(Prompt)
        fixture.call("web_fetch")
        runCurrent()
        val output = fixture.response()
        assertEquals(JsonPrimitive(false), output["success"])
        assertTrue(tools.completed.single().second.isError)
        assertEquals("n".repeat(2_000) + "\n\nUnavailable", output.output())
        fixture.runtime.close()
    }

    @Test
    fun `result hook failure preserves both successful search and failed fetch`() = runTest {
        val tools = Tools().apply { failAfter = true }
        val fixture = Fixture(this, Search().apply { failFetch = true }, tools = tools)
        fixture.open().feature(SendsPrompts).send(Prompt)
        fixture.call("web_search")
        runCurrent()
        val success = tools.completed.single().second
        assertEquals(toolResult(true, success.text), fixture.response())
        fixture.call("web_fetch")
        runCurrent()
        assertEquals(toolResult(false, "Unavailable"), fixture.response())
        fixture.runtime.close()
    }

    private suspend fun Fixture.call(name: String) = event(
        "item/tool/call",
        "turnId" to "native-turn".json(),
        "tool" to name.json(),
        "arguments" to arguments,
        id = JsonPrimitive(880),
    )

    private fun Fixture.response(): JsonObject = wire.written.last { it["id"] == JsonPrimitive(880) }.obj("result")
    private fun JsonObject.output(): String = ((get("contentItems") as JsonArray).single() as JsonObject)
        .text("text").orEmpty()

    private class Tools : ProfileAgentTools by NoAgentTools {
        var verdict: NativeVerdict = NativeVerdict.Allow
        var ask = false
        var failAfter = false
        var note: String? = null
        var context: AgentToolContext? = null
        var arguments: JsonObject? = null
        val completed = mutableListOf<Pair<AgentToolContext, AgentToolResult>>()
        override suspend fun authorizeHosted(
            context: AgentToolContext,
            name: String,
            arguments: JsonObject,
        ): NativeVerdict {
            this.context = context
            this.arguments = arguments
            if (ask && !context.permissions.request(AgentToolApproval(name, "Search", arguments.toString()))) {
                return NativeVerdict.Deny("Permission denied")
            }
            return verdict
        }
        override suspend fun afterHosted(
            context: AgentToolContext,
            name: String,
            arguments: JsonObject,
            result: AgentToolResult,
        ): String? {
            completed += context to result
            if (failAfter) error("Hook failed")
            return note
        }
    }

    private class Search : SearchEngine {
        var calls = 0
        var failFetch = false
        override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
            calls++
            return listOf(SearchResult("https://example.com", "Title", "Snippet"))
        }
        override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent {
            calls++
            if (failFetch) error("Provider failed")
            return ResourceContent(url, "Title", "Body")
        }
    }

    private companion object {
        val arguments = json("query" to "topic".json(), "url" to "https://example.com".json())
    }
}
