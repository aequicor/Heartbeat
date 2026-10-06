package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeConfirmation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ClaudeNativeSessionTest {
    @Test
    fun `gated session establishes acceptance and keeps permission visible after late assistant frame`() = runTest {
        for (isCancelled in listOf(false, true)) {
            val fixture = ClaudeFixture(backgroundScope)
            fixture.transport.versionValue = "2.1.285"
            lateinit var pipe: Pipe
            fixture.transport.duplexGeneration = { args, session ->
                val id = args.first { it.startsWith("--session-id=") }.substringAfter('=')
                pipe = Pipe(id)
                session(pipe)
                0
            }
            var preflights = 0
            val tools = object : ProfileAgentTools by NoAgentTools {
                override suspend fun nativeTools(scope: ToolPolicyScope) = ResolvedToolPolicy(nativeOn = setOf("Bash"))
                override suspend fun prepareNative(context: AgentToolContext, call: NativeToolCall): NativePreparation {
                    preflights++
                    assertEquals(TrustLevel.Ask, context.trust)
                    assertEquals("echo example", call.command)
                    return NativePreparation.Ask(
                        NativeConfirmation {
                            if (context.permissions.request(
                                    AgentToolApproval("Bash", "Run command", call.arguments.toString()),
                                )
                            ) {
                                NativeVerdict.Allow
                            } else {
                                NativeVerdict.Deny("Denied")
                            }
                        },
                    )
                }
            }
            val runtime = fixture.runtime(tools, native = ClaudeNativeSupport { nativeWorkspace() })
            val session = runtime.create(CreateSessionRequest(testTarget, WorkspaceRef("project")))
            val submitted = async { session.features.available(SendsPrompts).send(prompt()) }
            runCurrent()
            val turn = submitted.await()
            val pending = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
            pipe.frames.send(frame(assistantFrame(session.ref.nativeId)))
            runCurrent()
            val stillPending = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
            assertEquals(pending, stillPending.requests.single())
            if (isCancelled) {
                pipe.frames.send(frame("""{"type":"control_cancel_request","request_id":"permission"}"""))
                runCurrent()
                val running = assertIs<ActiveSessionState.Running>(session.state.value)
                assertTrue(pending.id in running.turn.resolvedPermissions)
            }
            session.features.available(RequestsPermissions)
                .respond(PermissionDecision(turn, pending.id, pending.options.first().id))
            runCurrent()
            if (isCancelled) {
                assertEquals(null, pipe.decision)
                pipe.frames.send(frame(resultFrame(session.ref.nativeId)))
                runCurrent()
            } else {
                assertEquals("allow", pipe.decision?.text("behavior"))
            }
            assertEquals(1, preflights)
            assertIs<ActiveSessionState.Ready>(session.state.value)
            val sentTypes = listOf("control_request", "user", "control_response") +
                if (isCancelled) emptyList() else listOf("control_response")
            assertEquals(sentTypes, pipe.sentTypes)
            val args = fixture.transport.calls.last()
            assertTrue("--permission-prompt-tool=stdio" in args)
            assertTrue("--permission-mode=default" in args)
            assertEquals(setOf("Bash") + ClaudeAgentTools, claudeToolFlags(args).native)
            runtime.close()
        }
    }

    @Test
    fun `additional native tools remain disabled without project toggle supported version or native support`() =
        runTest {
            val tools = object : ProfileAgentTools by NoAgentTools {
                override suspend fun nativeTools(scope: ToolPolicyScope) = ResolvedToolPolicy(nativeOn = setOf("Read"))
            }
            for (missing in listOf("project", "toggle", "version", "support")) {
                val fixture = ClaudeFixture(backgroundScope)
                fixture.transport.versionValue = if (missing == "version") "2.1.284" else "2.1.285"
                fixture.toggles.isNativeEnabled = missing != "toggle"
                val native = if (missing ==
                    "support"
                ) {
                    MissingClaudeNativeSupport
                } else {
                    ClaudeNativeSupport { nativeWorkspace() }
                }
                val runtime = fixture.runtime(tools, native = native)
                val workspace = WorkspaceRef("project").takeUnless { missing == "project" }
                val session = runtime.create(CreateSessionRequest(testTarget, workspace))
                session.features.available(SendsPrompts).send(prompt())
                runCurrent()
                assertIs<ActiveSessionState.Ready>(session.state.value)
                val args = fixture.transport.calls.last()
                assertFalse("Read" in claudeToolFlags(args).native)
                assertFalse(args.any { "permission-prompt-tool" in it })
                runtime.close()
            }
        }

    private class Pipe(private val id: String) : ClaudeDuplex {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val sentTypes = mutableListOf<String>()
        var decision: JsonObject? = null
        override suspend fun receive() = frames.receiveCatching().getOrNull()
        override suspend fun closeInput() {
            frames.close()
        }
        override suspend fun send(frame: JsonObject) {
            sentTypes += checkNotNull(frame.text("type"))
            val requestId = frame.text("request_id")
            when (frame.text("type")) {
                "control_request" -> frames.send(
                    frame(
                        """{"type":"control_response","response":{
                    "subtype":"success","request_id":"$requestId","response":{"hooks_applied":true}}}""",
                    ),
                )

                "user" -> frames.send(
                    frame(
                        """{"type":"control_request","request_id":"hook","request":{
                    "subtype":"hook_callback","callback_id":"heartbeat-native-pre-tool","tool_use_id":"call",
                    "input":{"session_id":"$id","hook_event_name":"PreToolUse","tool_name":"Bash",
                    "tool_use_id":"call","tool_input":{"command":"echo example"}}}}""",
                    ),
                )

                "control_response" -> {
                    val response = frame["response"] as JsonObject
                    if (response.text("request_id") == "hook") {
                        frames.send(
                            frame(
                                """{"type":"control_request","request_id":"permission","request":{
                            "subtype":"can_use_tool","tool_use_id":"call","tool_name":"Bash",
                            "input":{"command":"echo example"}}}""",
                            ),
                        )
                    } else {
                        decision = response["response"] as JsonObject
                        frames.send(frame(resultFrame(id)))
                    }
                }
            }
        }
    }
}
private fun frame(text: String) = Json.parseToJsonElement(text) as JsonObject
