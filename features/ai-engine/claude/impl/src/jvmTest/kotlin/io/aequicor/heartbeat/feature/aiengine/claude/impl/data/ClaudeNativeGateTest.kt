package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeConfirmation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeCallClassifier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ClaudeNativeGateTest {
    @Test
    fun `automatic edit containment is checked again before both native allow transports`() = runTest {
        for (isPreflight in listOf(false, true)) {
            var isInside = true
            var checks = 0
            val workspace = ClaudeNativeWorkspace(
                "/project",
                object : NativeCallClassifier {
                    override fun terminatesHost(command: String) = false
                    override suspend fun isWorkspaceEdit(path: String, workspace: String): Boolean {
                        checks++
                        return isInside
                    }
                },
            )
            val tools = object : ProfileAgentTools by NoAgentTools {
                override suspend fun prepareNative(context: AgentToolContext, call: NativeToolCall): NativePreparation {
                    assertNull(call.automaticDenial)
                    isInside = false
                    return NativePreparation.Allow
                }
                override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                    assertNull(call.automaticDenial)
                    isInside = false
                    return NativeVerdict.Allow
                }
            }
            val gate = gate(tools, workspace, AgentToolDecisionMode.AutomaticSafety, "Write")
            val request = frame(
                (if (isPreflight) hook() else permission()).toString()
                    .replace("\"Bash\"", "\"Write\"")
                    .replace("\"command\":\"echo example\"", "\"file_path\":\"/project/file\""),
            )
            val response = gate.handle(request)
            val result = if (isPreflight) hookOutput(response).text("permissionDecision") else response.text("behavior")
            assertEquals("deny", result)
            assertEquals(2, checks)
        }
    }

    @Test
    fun `initialize must confirm hook registration`() {
        val gate = gate(Tools())
        for (response in listOf("{}", """{"hooks_applied":false}""", """{"hooks_applied":"true"}""")) {
            assertFailsWith<EngineException> { gate.initialized(frame(response)) }
        }
        gate.initialized(frame("""{"hooks_applied":true}"""))
    }

    @Test
    fun `ask continuation preserves exact input and calls the preflight only once`() = runTest {
        val tools = Tools()
        val gate = gate(tools)
        assertEquals("ask", hookOutput(gate.handle(hook())).text("permissionDecision"))
        val answer = gate.handle(permission())
        assertEquals("allow", answer.text("behavior"))
        assertEquals(permission()["input"], answer["updatedInput"])
        assertEquals(1, tools.preflights)
        assertEquals(1, tools.confirmations)
        assertEquals(0, tools.authorizations)
        assertEquals("deny", gate.handle(permission()).text("behavior"))
    }

    @Test
    fun `changed full input invalidates ticket even when command is unchanged`() = runTest {
        val tools = Tools()
        val gate = gate(tools)
        gate.handle(hook())
        gate.handle(permission("changed description"))
        assertEquals(0, tools.confirmations)
        assertEquals(1, tools.authorizations)
    }

    @Test
    fun `deny persists for unexpected permission callback and close rejects later callbacks`() = runTest {
        val tools = Tools().apply { decision = NativePreparation.Deny("hook denied") }
        val gate = gate(tools)
        assertEquals("deny", hookOutput(gate.handle(hook())).text("permissionDecision"))
        assertEquals("deny", gate.handle(permission()).text("behavior"))
        assertEquals(0, tools.authorizations)
        gate.close()
        assertFailsWith<EngineException> { gate.handle(hook()) }
    }

    @Test
    fun `foreign session and callback cannot reach authorization`() = runTest {
        val tools = Tools()
        val gate = gate(tools)
        for (bad in listOf(
            frame("""{"session_id":"foreign"}"""),
            frame("""{"request":{"subtype":"hook_callback","input":{"session_id":"foreign"}}}"""),
        )) {
            assertFailsWith<EngineException> { gate.validate(bad) }
        }
        assertEquals(
            "deny",
            hookOutput(
                gate.handle(frame(hook().toString().replace("heartbeat-native-pre-tool", "foreign"))),
            ).text("permissionDecision"),
        )
        assertEquals(0, tools.preflights)
    }

    @Test
    fun `failed and timed out preflights explicitly deny instead of returning a nonblocking hook error`() = runTest {
        for (isTimeout in listOf(false, true)) {
            val tools = object : ProfileAgentTools by NoAgentTools {
                override suspend fun prepareNative(context: AgentToolContext, call: NativeToolCall): NativePreparation {
                    if (isTimeout) kotlinx.coroutines.awaitCancellation()
                    error("private failure")
                }
            }
            val gate = gate(tools)
            assertEquals("deny", hookOutput(gate.handle(hook())).text("permissionDecision"))
            assertEquals("deny", gate.handle(permission()).text("behavior"))
        }
    }

    @Test
    fun `classification preserves read trust and restricts edits and host termination`() = runTest {
        val workspace = nativeWorkspace()
        val read = assertNotNull(workspace.classify("Read", frame("""{"file_path":"/other/file"}""")))
        assertTrue(read.covered(TrustLevel.Ask))
        val edit = assertNotNull(workspace.classify("Write", frame("""{"file_path":"/project/file"}""")))
        assertEquals(AgentToolAction.Edit, edit.action)
        assertTrue(edit.covered(TrustLevel.AutoEdits))
        assertNull(edit.automaticDenial)
        val outside = assertNotNull(workspace.classify("Edit", frame("""{"file_path":"relative"}""")))
        assertFalse(outside.covered(TrustLevel.AutoEdits))
        assertNotNull(outside.automaticDenial)
        assertNotNull(workspace.classify("Write", frame("""{"file_path":"/outside/file"}"""))?.automaticDenial)
        val command = assertNotNull(workspace.classify("Bash", frame("""{"command":"kill host"}""")))
        assertFalse(command.covered(TrustLevel.Full))
        assertNotNull(command.automaticDenial)
        assertEquals("/project", command.workingDirectory)
        assertNull(workspace.classify("Agent", frame("{}")))
        assertNull(workspace.classify("Bash", frame("{}")))
    }

    @Test
    fun `Bash arguments cannot attest to the runtime shell environment`() = runTest {
        val call = assertNotNull(
            nativeWorkspace().classify(
                "Bash",
                frame("""{"command":"git push origin branch","commandEnvironment":{"PATH":"/fake"}}"""),
            ),
        )
        assertEquals("git push origin branch", call.command)
        assertNull(call.commandEnvironment)
    }

    @Test
    fun `unknown old and prerelease versions cannot enable native gate`() {
        for (version in listOf(null, "2.1.284", "2.0.999", "garbage", "2.1.285-beta", "99999999999.0.0")) {
            assertFalse(supportsClaudeNativeGate(version), version)
        }
        for (version in listOf("2.1.285", "2.1.286", "2.2.0", "3.0.0")) {
            assertTrue(supportsClaudeNativeGate(version), version)
        }
    }

    private class Tools : ProfileAgentTools by NoAgentTools {
        var preflights = 0
        var confirmations = 0
        var authorizations = 0
        var decision: NativePreparation = NativePreparation.Ask(
            NativeConfirmation {
                confirmations++
                NativeVerdict.Allow
            },
        )
        override suspend fun prepareNative(context: AgentToolContext, call: NativeToolCall): NativePreparation {
            preflights++
            assertEquals("native", context.session.nativeId)
            assertEquals(ToolCallId("call"), context.callId)
            assertEquals(hook()["input"]?.let { (it as JsonObject)["tool_input"] }, call.arguments)
            assertEquals("/project", call.workingDirectory)
            return decision
        }
        override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
            authorizations++
            return NativeVerdict.Deny("new approval needed")
        }
    }
}

internal fun nativeWorkspace() = ClaudeNativeWorkspace(
    "/project",
    object : NativeCallClassifier {
        override fun terminatesHost(command: String) = command == "kill host"
        override suspend fun isWorkspaceEdit(path: String, workspace: String) = path == "/project/file"
    },
)
private fun gate(
    tools: ProfileAgentTools,
    workspace: ClaudeNativeWorkspace = nativeWorkspace(),
    decisionMode: AgentToolDecisionMode = AgentToolDecisionMode.Interactive,
    tool: String = "Bash",
) = ClaudeNativeGate("native", setOf(tool), workspace, tools) {
    AgentToolContext(
        SessionRef(testTarget.engine, SessionSourceId("test"), "native"),
        WorkspaceRef("project"),
        TurnId("turn"),
        prompt().id,
        TrustLevel.Ask,
        decisionMode = decisionMode,
    )
}
private fun frame(text: String) = Json.parseToJsonElement(text) as JsonObject
private fun hookOutput(frame: JsonObject) = frame["hookSpecificOutput"] as JsonObject
private fun permission(description: String = "description") = frame(
    """{
    "subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"call",
    "input":{"command":"echo example","description":"$description"}
}""",
)
private fun hook() = frame(
    """{
    "subtype":"hook_callback","callback_id":"heartbeat-native-pre-tool","tool_use_id":"call",
    "input":{"session_id":"native","hook_event_name":"PreToolUse","tool_name":"Bash","tool_use_id":"call",
    "tool_input":{"command":"echo example","description":"description"}}
}""",
)
