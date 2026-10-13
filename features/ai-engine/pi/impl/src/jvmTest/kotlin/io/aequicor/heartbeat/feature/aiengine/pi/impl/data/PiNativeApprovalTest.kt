package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeCallClassifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiNativeApprovalTest {
    @Test
    fun `outside unpinned and detached edits carry a deterministic automatic refusal`() = runTest {
        val scenarios = listOf(
            WorkspaceRef("project") to "/outside/file",
            WorkspaceRef("project") to null,
            null to TestWorkspace.resolve("file").toString(),
        )
        for ((workspace, path) in scenarios) {
            val tools = object : ProfileAgentTools by NoAgentTools {
                override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                    assertNotNull(call.automaticDenial)
                    return NativeVerdict.Deny(checkNotNull(call.automaticDenial))
                }
            }
            val fixture = fixture(this, tools = tools, project = workspace)
            fixture.runningTurn(TrustLevel.Full)
            fixture.connection.event(approval("edit", "file", "write", path))
            runCurrent()
            assertEquals(listOf(answer("edit", "confirmed", false)), fixture.connection.sent)
            fixture.session.shutdown()
        }
    }

    @Test
    fun `a path that escapes its workspace during review is refused before Pi receives allow`() = runTest {
        var isInside = true
        var classifications = 0
        val classifier = object : NativeCallClassifier {
            override fun terminatesHost(command: String) = false
            override suspend fun isWorkspaceEdit(path: String, workspace: String): Boolean {
                classifications++
                return isInside
            }
        }
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                assertNull(call.automaticDenial)
                isInside = false
                return NativeVerdict.Allow
            }
        }
        val fixture = fixture(this, tools = tools, nativeCalls = classifier)
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(prompt("automatic").copy(decisionMode = AgentToolDecisionMode.AutomaticSafety))
        fixture.connection.event(record("""{"type":"agent_start"}"""))
        fixture.connection.event(approval("edit", "file", "write", TestWorkspace.resolve("file").toString()))
        runCurrent()
        assertEquals(2, classifications)
        assertEquals(listOf(answer("edit", "confirmed", false)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `automatic native ingress retains full arguments call identity directory and accepted mode`() = runTest {
        val arguments = record("""{"command":"true","timeout":30,"extra":"complete-input"}""")
        var calls = 0
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                calls++
                assertEquals(AgentToolDecisionMode.AutomaticSafety, context.decisionMode)
                assertEquals(ToolCallId("c1"), context.callId)
                assertEquals(arguments, call.arguments)
                assertEquals("true", call.command)
                assertEquals(TestWorkspace.toString(), call.workingDirectory)
                assertEquals("anthropic/test", context.target?.model?.value)
                return NativeVerdict.Allow
            }
        }
        val fixture = fixture(this, tools = tools)
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(prompt("automatic").copy(decisionMode = AgentToolDecisionMode.AutomaticSafety))
        fixture.connection.event(record("""{"type":"agent_start"}"""))
        val request = approval("automatic", "true")
        val payload = record(request.string("message")!!)
        fixture.connection.event(
            JsonObject(
                request + ("message" to JsonPrimitive(JsonObject(payload + ("arguments" to arguments)).toString())),
            ),
        )
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf(answer("automatic", "confirmed", true)), fixture.connection.sent)
        assertEquals(
            AgentToolDecisionMode.AutomaticSafety,
            assertIs<ActiveSessionState.Running>(fixture.session.state.value).turn.decisionMode,
        )
        fixture.session.shutdown()
    }

    @Test
    fun `search uses hosted authorization and returns hook context without a user dialog`() = runTest {
        val arguments = JsonObject(mapOf("query" to JsonPrimitive("original query")))
        var authorized = false
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun authorizeHosted(
                context: AgentToolContext,
                name: String,
                arguments: JsonObject,
            ): NativeVerdict {
                assertEquals("web_search", name)
                assertEquals("original query", arguments.string("query"))
                authorized = true
                return NativeVerdict.Allow
            }
            override suspend fun afterHosted(
                context: AgentToolContext,
                name: String,
                arguments: JsonObject,
                result: AgentToolResult,
            ): String {
                assertTrue(authorized)
                assertEquals("original query", arguments.string("query"))
                assertEquals(AgentToolResult("failed search", isError = true), result)
                return "Useful hook context"
            }
        }
        val fixture = fixture(this, tools = tools)
        fixture.runningTurn(TrustLevel.Full)
        val request = approval("search", "original query", "web_search")
        val payload = record(request.string("message")!!)
        fixture.connection.event(
            JsonObject(
                request + ("message" to JsonPrimitive(JsonObject(payload + ("arguments" to arguments)).toString())),
            ),
        )
        runCurrent()
        assertEquals(listOf(answer("search", "confirmed", true)), fixture.connection.sent)
        val result = JsonObject(
            mapOf(
                "toolName" to JsonPrimitive("web_search"),
                "arguments" to arguments,
                "result" to JsonPrimitive("failed search"),
                "isError" to JsonPrimitive(true),
            ),
        )
        fixture.connection.event(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("extension_ui_request"),
                    "id" to JsonPrimitive("result"),
                    "method" to JsonPrimitive("input"),
                    "title" to JsonPrimitive("heartbeat.tool-result"),
                    "placeholder" to JsonPrimitive(result.toString()),
                ),
            ),
        )
        runCurrent()
        assertEquals("Useful hook context", fixture.connection.sent.last().string("value"))
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `slow gate does not block reader and late allowance cannot reach a later turn`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                return NativeVerdict.Allow
            }
        }
        val fixture = fixture(this, tools = tools)
        fixture.runningTurn(TrustLevel.Full)
        fixture.connection.event(approval("slow"))
        runCurrent()
        assertTrue(entered.isCompleted)
        // The same event callback must still consume the terminal barrier while approval waits.
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.runningTurn(TrustLevel.Full)
        release.complete(Unit)
        runCurrent()
        assertTrue(fixture.connection.sent.isEmpty())
        fixture.session.shutdown()
    }

    @Test
    fun `read calls reach the gate and an ask verdict preserves native permissions`() = runTest {
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                assertEquals(AgentToolAction.Read, call.action)
                assertEquals(listOf("notes.md"), call.paths)
                assertTrue(call.covered(TrustLevel.Ask))
                val allowed = context.permissions.request(AgentToolApproval(call.name, "Read", "Hook asks for review"))
                return if (allowed) NativeVerdict.Allow else NativeVerdict.Deny("Declined")
            }
        }
        val fixture = fixture(this, tools = tools)
        val turn = fixture.runningTurn(TrustLevel.Full)
        fixture.connection.event(approval("read", "notes.md", "read"))
        runCurrent()
        val waiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        assertEquals("Hook asks for review", waiting.requests.single().description)
        fixture.session.respond(PermissionDecision(turn, PermissionRequestId("read"), PiApprovalAllow))
        runCurrent()
        assertEquals(listOf(answer("read", "confirmed", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `gate denial defeats full trust and unknown tools cannot inherit it`() = runTest {
        var calls = 0
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                calls++
                assertTrue(call.covered(context.trust))
                return NativeVerdict.Deny("Policy or hook denied")
            }
        }
        val fixture = fixture(this, tools = tools)
        fixture.runningTurn(TrustLevel.Full)
        fixture.connection.event(approval("denied"))
        fixture.connection.event(approval("unknown", tool = "unknown"))
        runCurrent()
        assertEquals(1, calls)
        assertEquals(
            listOf(answer("denied", "confirmed", false), answer("unknown", "confirmed", false)),
            fixture.connection.sent,
        )
        fixture.session.shutdown()
    }

    @Test
    fun `file targets are never scanned as shell commands and unpinned edits need approval`() = runTest {
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                assertEquals(AgentToolAction.Edit, call.action)
                assertEquals(null, call.command)
                assertTrue(call.covered(TrustLevel.Full))
                assertFalse(call.covered(TrustLevel.AutoEdits))
                return NativeVerdict.Allow
            }
        }
        val fixture = fixture(this, tools = tools)
        fixture.runningTurn(TrustLevel.Full)
        fixture.connection.event(approval("write", "taskkill /F", "write"))
        runCurrent()
        assertEquals(listOf(answer("write", "confirmed", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `a response queued for writing is revoked when its turn settles`() = runTest {
        val fixture = fixture(this)
        fixture.runningTurn(TrustLevel.Full)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.connection.beforeSend = {
            writing.complete(Unit)
            release.await()
        }
        fixture.connection.event(approval("queued"))
        runCurrent()
        assertTrue(writing.isCompleted)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertTrue(fixture.connection.sent.isEmpty())
        fixture.session.shutdown()
    }

    @Test
    fun `failed native approval writes terminate the waiting process for manual and trusted answers`() = runTest {
        for (trust in listOf(TrustLevel.Ask, TrustLevel.Full)) {
            val fixture = fixture(this)
            val turn = fixture.runningTurn(trust)
            fixture.connection.sendFailure = EngineException(EngineFailure.Unknown())
            fixture.connection.event(approval("broken"))
            runCurrent()
            if (trust == TrustLevel.Ask) {
                fixture.session.respond(PermissionDecision(turn, PermissionRequestId("broken"), PiApprovalAllow))
                runCurrent()
            }
            assertTrue(fixture.connection.isClosed)
            assertTrue(fixture.connection.sent.isEmpty())
            assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value)
            fixture.session.shutdown()
        }
    }
}
