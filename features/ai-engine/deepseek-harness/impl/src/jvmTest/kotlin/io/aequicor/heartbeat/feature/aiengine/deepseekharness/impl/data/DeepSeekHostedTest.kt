package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOption
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeepSeekHostedTest {
    @Test
    fun `detached tools require opt in and catalog discovery never attaches them`() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.create(CreateSessionRequest(h.target))
        h.runtime.discoverCatalog(h.target.binding)
        assertTrue(h.bridge.callbacks.isEmpty())
        assertTrue(h.connection.servers.all { it.isEmpty() })
    }

    @Test
    fun `create declares scoped MCP and carries the user preselection`() = runTest {
        val h = Harness(backgroundScope)
        h.open(preselected = mapOf("harness" to setOf("chosen")))
        assertEquals(setOf("chosen"), h.tools.scopes.single().preselected["harness"])
        assertEquals(setOf("questionnaire_ask"), h.bridge.scopes.single().declared)
        assertEquals("http", h.connection.servers.single().single()["type"].toString().trim('"'))
    }

    @Test
    fun `admitted call supplies exact context once without granting native action permission`() = runTest {
        val h = Harness(backgroundScope)
        val lease = h.open()
        val turn = h.send(lease, "request")
        assertIs<AcpPermissionOutcome.Selected>(h.admit(lease, "call"))
        val context = assertNotNull(h.bridge.callbacks.single()("call", "questionnaire_ask"))
        assertEquals(lease.ref, context.session)
        assertEquals(turn, context.turn)
        assertEquals(RequestId("request"), context.request)
        assertEquals(ToolCallId("call"), context.callId)
        assertEquals(0, h.tools.nativeAuthorizations)
        assertNull(h.bridge.callbacks.single()("call", "questionnaire_ask"))
        assertEquals(AcpPermissionOutcome.Cancelled, h.permission(lease, "call"))
    }

    @Test
    fun `wrong name consumes the binding and another session cannot use it`() = runTest {
        val h = Harness(backgroundScope)
        val a = h.open()
        val b = h.open()
        h.send(a, "a")
        h.send(b, "b")
        h.admit(a, "call")
        assertNull(h.bridge.callbacks[1]("call", "questionnaire_ask"))
        assertNull(h.bridge.callbacks[0]("call", "other"))
        assertNull(h.bridge.callbacks[0]("call", "questionnaire_ask"))
    }

    @Test
    fun `cancel revokes consumed context and late calls cannot borrow the next turn`() = runTest {
        val h = Harness(backgroundScope)
        val lease = h.open()
        val turn = h.send(lease, "first")
        h.admit(lease, "consumed")
        val context = assertNotNull(h.bridge.callbacks.single()("consumed", "questionnaire_ask"))
        h.admit(lease, "late")
        lease.features.requireFeature(CancelsTurns).cancel(turn)
        assertFalse(context.lifetime!!.isActive)
        assertNull(h.bridge.callbacks.single()("late", "questionnaire_ask"))
        h.connection.results.getValue(lease.ref.nativeId).complete(AcpPromptResult("cancelled"))
        runCurrent()
        h.send(lease, "second")
        assertNull(h.bridge.callbacks.single()("late", "questionnaire_ask"))
        assertEquals(AcpPermissionOutcome.Cancelled, h.admit(lease, "consumed"))
    }

    @Test
    fun `tool completion revokes its context before the whole turn completes`() = runTest {
        val h = Harness(backgroundScope)
        val lease = h.open()
        h.send(lease, "request")
        h.admit(lease, "call")
        val context = assertNotNull(h.bridge.callbacks.single()("call", "questionnaire_ask"))
        h.update(lease, """{"sessionUpdate":"tool_call_update","toolCallId":"call","status":"completed"}""")
        assertFalse(context.lifetime!!.isActive)
    }

    @Test
    fun `terminal ACP tool content is retained as a result for the chat`() = runTest {
        val h = Harness(backgroundScope)
        val lease = h.open()
        val turn = h.send(lease, "request")
        h.admit(lease, "call")
        h.update(
            lease,
            """{"sessionUpdate":"tool_call_update","toolCallId":"call","status":"completed",
                "content":[{"type":"content","content":{"type":"text","text":"Answered: yes"}}]}""",
        )
        val items = lease.features.requireFeature(SessionHistory).page(HistoryPageRequest()).items
        val result = items.filterIsInstance<SessionItem.ToolResult>().single()
        assertEquals(ToolCallId("call"), result.call)
        assertEquals(turn, result.info.turn)
        assertEquals(listOf(ContentPart.Text("Answered: yes")), result.parts)
        assertNull(result.failure)
    }

    @Test
    fun `close revokes attachment and resume uses a fresh scoped attachment`() = runTest {
        val h = Harness(backgroundScope)
        val lease = h.open()
        val ref = lease.ref
        lease.close()
        assertEquals(1, h.bridge.closed)
        h.runtime.attach(ref, ResumeSessionRequest(h.target, areDetachedToolsEnabled = true))
        assertEquals(2, h.bridge.callbacks.size)
        assertEquals(ref, h.bridge.scopes.last().session)
        assertTrue(h.connection.resumedServers.single().isNotEmpty())
        h.runtime.close()
        assertEquals(2, h.bridge.closed)
    }

    @Test
    fun `failed creation revokes its capability`() = runTest {
        val h = Harness(backgroundScope)
        h.connection.failCreate = true
        assertFailsWith<IllegalStateException> { h.open() }
        assertEquals(1, h.bridge.closed)
    }

    @Test
    fun `DSH names preserve short identities and disambiguate lossy names`() {
        assertEquals("mcp__heartbeat_tools__questionnaire_ask", dshHostedPublicName("questionnaire_ask"))
        assertEquals(64, dshHostedPublicName("x".repeat(80)).length)
        assertNotEquals(dshHostedPublicName("a.b"), dshHostedPublicName("a_b"))
    }

    private class Harness(scope: CoroutineScope) {
        val target = EngineTarget(DeepSeekEngine.Id, EngineBindingId("binding"), ModelId("deepseek-flash"))
        val connection = Connection()
        val bridge = Bridge()
        val tools = Tools()
        val runtime = DeepSeekRuntime(
            RuntimeIdentity(DeepSeekEngine.Id, AuthSourceId("auth"), AuthRevision.Unknown),
            connection,
            scope,
            { "C:\\workspace" },
            tools = tools,
            bridge = bridge,
        ).also { connection.submitted = it::onPromptSubmitted }

        suspend fun open(preselected: Map<String, Set<String>> = emptyMap()) = runtime.create(
            CreateSessionRequest(target, areDetachedToolsEnabled = true, preselected = preselected),
        )

        suspend fun send(lease: ActiveSession, id: String): TurnId = lease.features.requireFeature(SendsPrompts).send(
            PromptRequest(RequestId(id), listOf(ContentPart.Text("test"))),
        )

        suspend fun update(lease: ActiveSession, value: String) = runtime.onSessionUpdate(
            AcpSessionUpdate(lease.ref.nativeId, json(value)),
        )

        suspend fun admit(lease: ActiveSession, call: String): AcpPermissionOutcome {
            update(
                lease,
                """{"sessionUpdate":"tool_call","toolCallId":"$call",
                    "title":"mcp__heartbeat_tools__questionnaire_ask","kind":"other",
                    "rawInput":{},"status":"in_progress"}""",
            )
            return permission(lease, call)
        }

        suspend fun permission(lease: ActiveSession, call: String) = runtime.requestPermission(
            AcpPermissionRequest(
                lease.ref.nativeId,
                json("""{"toolCallId":"$call"}"""),
                listOf(AcpPermissionOption("once", "Allow once", "allow_once")),
            ),
        )
    }

    private class Tools : ProfileAgentTools {
        val scopes = mutableListOf<AgentToolScope>()
        var nativeAuthorizations = 0
        override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
            AgentToolSpec("questionnaire_ask", "Question", json("""{"type":"object"}""")),
        )
        override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
            scopes += scope
            return specifications(scope.workspace)
        }
        override suspend fun instructions(workspace: WorkspaceRef?) = ""
        override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) = AgentToolResult(
            "ok",
        )
        override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
            nativeAuthorizations++
            return NativeVerdict.Deny("denied")
        }
    }

    private class Bridge : AgentToolBridge {
        override val isAvailable = true
        val scopes = mutableListOf<AgentToolScope>()
        val callbacks = mutableListOf<suspend (String, String) -> AgentToolContext?>()
        var closed = 0
        override suspend fun attach(workspace: WorkspaceRef?, context: suspend () -> AgentToolContext?) =
            error("Uncorrelated bridge must not be used")
        override suspend fun attachCorrelated(
            scope: AgentToolScope,
            context: suspend (String, String) -> AgentToolContext?,
        ): AgentToolBridgeAttachment {
            scopes += scope
            callbacks += context
            return object : AgentToolBridgeAttachment {
                override val endpoint = AgentToolBridgeEndpoint("http://127.0.0.1:1234", "test-capability")
                private var isClosed = false
                override fun close() {
                    if (!isClosed) closed++
                    isClosed = true
                }
            }
        }
    }

    private class Connection : AcpConnection {
        var submitted: (String) -> Unit = {}
        var failCreate = false
        val servers = mutableListOf<List<JsonObject>>()
        val resumedServers = mutableListOf<List<JsonObject>>()
        val results = mutableMapOf<String, CompletableDeferred<AcpPromptResult>>()
        private val metadata = json("""{"configOptions":[{"id":"model"}]}""")
        override suspend fun initialize(client: AcpImplementation) = AcpInitialization(1, json("{}"), emptyList())
        override suspend fun authenticate(methodId: String) = Unit
        override suspend fun newSession(cwd: String): AcpSession = newSession(cwd, emptyList())
        override suspend fun newSession(cwd: String, servers: List<JsonObject>): AcpSession {
            check(!failCreate)
            this.servers += servers
            return AcpSession("session-${this.servers.size}", metadata)
        }
        override suspend fun loadSession(sessionId: String, cwd: String) = AcpSession(sessionId, metadata)
        override suspend fun resumeSession(sessionId: String, cwd: String, servers: List<JsonObject>): AcpSession {
            resumedServers += servers
            return AcpSession(sessionId, metadata)
        }
        override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) = Unit
        override suspend fun closeSession(sessionId: String) = Unit
        override suspend fun prompt(sessionId: String, content: List<JsonObject>): AcpPromptResult {
            val result = CompletableDeferred<AcpPromptResult>()
            results[sessionId] = result
            submitted(sessionId)
            return result.await()
        }
        override suspend fun cancel(sessionId: String) = Unit
        override suspend fun close() = Unit
    }

    private companion object {
        fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
        fun <T : EngineFeature> EngineFeatures.requireFeature(key: EngineFeatureKey<T>): T =
            assertIs<FeatureAccess.Available<T>>(resolve(key)).feature
    }
}
