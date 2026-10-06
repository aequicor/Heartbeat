package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoogToolPolicyTest {
    @Test
    fun `search off filters declarations and a later refusal blocks already offered tools`() = runTest {
        val search = PolicySearch()
        val tools = PolicyTools()
        tools.denied = setOf("web_fetch")
        val offered = koogSearchToolset(search, tools, Context)
        assertEquals(listOf("web_search"), offered.map { it.descriptor.name })
        tools.verdict = NativeVerdict.Deny("Live Off")
        val refused = offered.single().run(Arguments)
        assertTrue(refused.isFailed)
        assertEquals("Live Off", refused.text)
        assertEquals(0, search.calls)
        assertEquals(Arguments, tools.arguments)
    }

    @Test
    fun `search hook can ask at full trust and append context to success or error without losing resources`() =
        runTest {
            val search = PolicySearch()
            val tools = PolicyTools()
            var permissions = 0
            tools.isAsking = true
            val context = Context.copy(
                permissions = AgentToolPermissions {
                    permissions++
                    assertEquals("Review query", it.description)
                    true
                },
            )
            val tool = koogSearchToolset(search, tools, context).first()
            val result = tool.run(Arguments)
            assertFalse(result.isFailed)
            assertTrue(result.text.startsWith("Hook context\n\n"))
            assertEquals("https://example.com", result.resources.single().id)
            assertEquals(Arguments, tools.arguments)
            search.isFailing = true
            val failed = tool.run(Arguments)
            assertTrue(failed.isFailed)
            assertEquals("Hook context\n\nUnavailable", failed.text)
            assertTrue(tools.result!!.isError)
            assertEquals(2, permissions)
        }

    @Test
    fun `cancelling the accepted turn stops a search waiting at the gate`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val lifetime = Job()
        val context = Context.copy(
            lifetime = lifetime,
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val tools = PolicyTools().apply { isAsking = true }
        val search = PolicySearch()
        val tool = koogSearchToolset(search, tools, context).first()
        val pending = async(lifetime) { tool.run(Arguments) }
        entered.await()
        lifetime.cancel()
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(0, search.calls)
    }

    @Test
    fun `detached declarations and instructions receive session scope on every turn`() = runTest {
        var declarations = setOf("first")
        val scopes = mutableListOf<AgentToolScope>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
                scopes += scope
                return declarations.map { AgentToolSpec(it, "description", JsonObject(emptyMap())) }
            }
            override suspend fun instructions(scope: AgentToolScope): String {
                scopes += scope
                assertEquals(declarations, scope.declared)
                return declarations.joinToString()
            }
        }
        assertEquals("first", detachedKoogWorkspace(tools, Context)?.instructions)
        declarations = setOf("second")
        assertEquals("second", detachedKoogWorkspace(tools, Context.copy(turn = TurnId("next")))?.instructions)
        assertTrue(scopes.all { it.session == Context.session && it.isRefreshedPerTurn })
    }
}

private class PolicyTools : ProfileAgentTools by NoAgentTools {
    var denied = emptySet<String>()
    var verdict: NativeVerdict = NativeVerdict.Allow
    var isAsking = false
    var arguments: JsonObject? = null
    var result: AgentToolResult? = null
    override suspend fun nativeTools(scope: ToolPolicyScope): ResolvedToolPolicy {
        assertEquals(Context.session, scope.session)
        return ResolvedToolPolicy(hostedDenied = denied)
    }
    override suspend fun authorizeHosted(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
    ): NativeVerdict {
        this.arguments = arguments
        if (isAsking && !context.permissions.request(AgentToolApproval(name, "Search", "Review query"))) {
            return NativeVerdict.Deny("Declined")
        }
        return verdict
    }
    override suspend fun afterHosted(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        result: AgentToolResult,
    ): String {
        this.result = result
        return "Hook context"
    }
}

private class PolicySearch : SearchEngine {
    var calls = 0
    var isFailing = false
    override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
        calls++
        if (isFailing) throw SearchException(SearchFailure.Unavailable)
        return listOf(SearchResult("https://example.com", "Title", "Text"))
    }
    override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent = error("No fetch expected")
}

private val Context = AgentToolContext(
    SessionRef(KoogEngineId, SessionSourceId("test"), "session"),
    null,
    TurnId("turn"),
    trust = TrustLevel.Full,
)
private val Arguments = JsonObject(mapOf("query" to JsonPrimitive("original query")))
