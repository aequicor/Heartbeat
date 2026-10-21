package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClaudeSearchGuardTest {
    @Test
    fun `search approval and result hooks receive the same full call and host bound turn`() = runTest {
        val tools = GuardTools()
        val context = context()
        val guard = ClaudeSearchGuard(tools, { context }, { true })
        var executions = 0
        val result = guard.execute("web_search", arguments) {
            assertEquals(1, tools.authorized.size)
            executions++
            AgentToolResult("result")
        }
        assertEquals("after\n\nresult", result.text)
        assertEquals(1, executions)
        val before = tools.authorized.single()
        val after = tools.completed.single()
        assertEquals(context.session, before.first.session)
        assertEquals(context.request, before.first.request)
        assertEquals(context.turn, before.first.turn)
        assertSame(context.lifetime, before.first.lifetime)
        assertNotNull(before.first.callId)
        assertEquals(arguments, before.second)
        assertEquals(before, after)
        context.lifetime?.cancel()
    }

    @Test
    fun `a denied shared gate cannot call search or its result hook`() = runTest {
        val tools = GuardTools().apply { verdict = NativeVerdict.Deny("Tool disabled") }
        val context = context()
        val guard = ClaudeSearchGuard(tools, { context }, { true })
        var executions = 0
        val result = guard.execute("web_fetch", arguments) {
            executions++
            AgentToolResult("unexpected")
        }
        assertTrue(result.isError)
        assertEquals(0, executions)
        assertTrue(tools.completed.isEmpty())
        context.lifetime?.cancel()
    }

    @Test
    fun `a stale generation after approval cannot execute search`() = runTest {
        var current = true
        val tools = GuardTools().apply { beforeAuthorize = { current = false } }
        val context = context()
        val guard = ClaudeSearchGuard(tools, { context }, { current })
        var executions = 0
        val result = guard.execute("web_search", arguments) {
            executions++
            AgentToolResult("unexpected")
        }
        assertTrue(result.isError)
        assertEquals(0, executions)
        context.lifetime?.cancel()
    }

    @Test
    fun `turn cancellation revokes an in flight permission request`() = runTest {
        val tools = GuardTools().apply { beforeAuthorize = { awaitCancellation() } }
        val context = context()
        val guard = ClaudeSearchGuard(tools, { context }, { true })
        var executions = 0
        val pending = async {
            guard.execute("web_search", arguments) {
                executions++
                AgentToolResult("unexpected")
            }
        }
        runCurrent()
        context.lifetime?.cancel()
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(0, executions)
        assertTrue(tools.completed.isEmpty())
    }

    @Test
    fun `missing context or native lifetime never authorizes a request`() = runTest {
        val tools = GuardTools()
        for (context in listOf(null, context().copy(lifetime = null))) {
            val guard = ClaudeSearchGuard(tools, { context }, { true })
            val result = guard.execute("web_search", arguments) { error("Must remain blocked") }
            assertTrue(result.isError)
        }
        assertTrue(tools.authorized.isEmpty())
    }

    @Test
    fun `result hook errors do not misreport an already completed search`() = runTest {
        val tools = GuardTools().apply { failsAfterResult = true }
        val context = context()
        val guard = ClaudeSearchGuard(tools, { context }, { true })
        val result = guard.execute("web_search", arguments) { AgentToolResult("completed") }
        assertFalse(result.isError)
        assertEquals("completed", result.text)
        context.lifetime?.cancel()
    }

    private class GuardTools : ProfileAgentTools by NoAgentTools {
        val authorized = mutableListOf<Pair<AgentToolContext, JsonObject>>()
        val completed = mutableListOf<Pair<AgentToolContext, JsonObject>>()
        var verdict: NativeVerdict = NativeVerdict.Allow
        var beforeAuthorize: suspend () -> Unit = {}
        var failsAfterResult = false
        override suspend fun authorizeHosted(
            context: AgentToolContext,
            name: String,
            arguments: JsonObject,
        ): NativeVerdict {
            authorized += context to arguments
            beforeAuthorize()
            return verdict
        }
        override suspend fun afterHosted(
            context: AgentToolContext,
            name: String,
            arguments: JsonObject,
            result: AgentToolResult,
        ): String {
            completed += context to arguments
            if (failsAfterResult) error("Sensitive result hook message")
            return "after"
        }
    }

    private fun context() = AgentToolContext(
        SessionRef(testTarget.engine, SessionSourceId("test"), "captured-session"),
        WorkspaceRef("project"),
        TurnId("captured-turn"),
        RequestId("captured-request"),
        lifetime = Job(),
    )

    private val arguments = Json.parseToJsonElement(
        """{"query":"topic","count":7,"session":"untrusted","turn":"untrusted"}""",
    ) as JsonObject
}
