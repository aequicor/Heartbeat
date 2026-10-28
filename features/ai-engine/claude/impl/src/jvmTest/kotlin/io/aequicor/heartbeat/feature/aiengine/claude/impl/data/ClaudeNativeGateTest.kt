package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_SEARCH_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    fun `hook matcher is anchored JavaScript regex and excludes both hosted bridges`() {
        val matcher = claudeNativeMatcher(setOf("Bash", CLAUDE_MCP_GROUP))
        assertTrue(matcher.startsWith("^(?:"))
        assertTrue(matcher.endsWith(")$"))
        assertFalse(matcher.contains("\\Q"))
        assertFalse(matcher.contains("\\E"))
        val regex = Regex(matcher)
        for (name in listOf("Bash", "mcp__pencil__execute", "mcp__foreign__read")) {
            assertTrue(regex.matches(name), name)
        }
        for (name in listOf(
            "BeforeBash",
            "BashAfter",
            "mcp__${HOSTED_TOOLS_SERVER}__run_command",
            "mcp__${HOSTED_SEARCH_SERVER}__web_fetch",
        )) {
            assertFalse(regex.matches(name), name)
        }
        val initialization = gate(Tools(), tool = CLAUDE_MCP_GROUP).initialization()
        val hooks = initialization["hooks"] as JsonObject
        val hook = (hooks["PreToolUse"] as JsonArray).single() as JsonObject
        assertEquals(claudeNativeMatcher(setOf(CLAUDE_MCP_GROUP)), hook.text("matcher"))
    }

    @Test
    fun `foreign MCP calls require the opt-in group and retain exact preflight confirmation`() = runTest {
        val tools = Tools()
        val foreignHook = frame(hook().toString().replace("Bash", "mcp__pencil__execute"))
        val foreignPermission = frame(permission().toString().replace("Bash", "mcp__pencil__execute"))
        val gate = gate(tools, tool = CLAUDE_MCP_GROUP)
        assertEquals("ask", hookOutput(gate.handle(foreignHook)).text("permissionDecision"))
        assertEquals("allow", gate.handle(foreignPermission).text("behavior"))
        assertEquals(1, tools.preflights)
        assertEquals(1, tools.confirmations)
        val classified = assertNotNull(nativeWorkspace().classify("mcp__pencil__execute", frame("{}")))
        assertEquals(CLAUDE_MCP_GROUP, classified.name)
        assertEquals("mcp__pencil__execute", classified.command)
        assertEquals("deny", hookOutput(gate(Tools()).handle(foreignHook)).text("permissionDecision"))
        gate.close()
        assertFailsWith<EngineException> { gate.handle(foreignPermission) }
    }

    @Test
    fun `MCP group never preapproves foreign calls or intercepts Heartbeat bridges`() {
        assertFalse(
            isForeignClaudeMcp(
                "mcp__${io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER}__run_command",
            ),
        )
        assertFalse(
            isForeignClaudeMcp(
                "mcp__${io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_SEARCH_SERVER}__web_fetch",
            ),
        )
        val args = ClaudeToolFlags(native = setOf(CLAUDE_MCP_GROUP)).arguments()
        assertEquals(listOf("--tools="), args.filter { it.startsWith("--tools=") })
        assertFalse(args.any { it.startsWith("--allowedTools=") })
        assertTrue("--strict-mcp-config" in claudeArguments())
        assertTrue("--strict-mcp-config" in claudeArguments(mcp = true))
        assertTrue(NATIVE_MCP_MARKER in claudeArguments(mcp = true))
    }

    @Test
    fun `shared allowance is honored by both native transports`() = runTest {
        for (isPreflight in listOf(false, true)) {
            val tools = object : ProfileAgentTools by NoAgentTools {
                override suspend fun prepareNative(context: AgentToolContext, call: NativeToolCall) =
                    NativePreparation.Allow
                override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall) =
                    NativeVerdict.Allow
            }
            val gate = gate(tools, nativeWorkspace(), AgentToolDecisionMode.AutomaticSafety, "Write")
            val request = frame(
                (if (isPreflight) hook() else permission()).toString()
                    .replace("\"Bash\"", "\"Write\"")
                    .replace("\"command\":\"echo example\"", "\"file_path\":\"/outside/file\""),
            )
            val response = gate.handle(request)
            val result = if (isPreflight) hookOutput(response).text("permissionDecision") else response.text("behavior")
            assertEquals("allow", result)
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
    fun `classification retains complete paths and commands for the shared gate`() = runTest {
        val workspace = nativeWorkspace()
        val read = assertNotNull(workspace.classify("Read", frame("""{"file_path":"/other/file"}""")))
        assertEquals(AgentToolAction.Read, read.action)
        val edit = assertNotNull(workspace.classify("Write", frame("""{"file_path":"/project/file"}""")))
        assertEquals(AgentToolAction.Edit, edit.action)
        val outside = assertNotNull(workspace.classify("Edit", frame("""{"file_path":"relative"}""")))
        assertEquals(listOf("relative"), outside.paths)
        val command = assertNotNull(workspace.classify("Bash", frame("""{"command":"kill host"}""")))
        assertEquals("kill host", command.command)
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

internal fun nativeWorkspace() = ClaudeNativeWorkspace("/project")
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
