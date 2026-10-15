package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiToolPlanTest {
    @Test
    fun `policy changes deny inspection live and refresh declarations between turns`() = runTest {
        val tools = PlanTools()
        val fixture = fixture(
            this,
            tools = tools,
        ) { _, connection -> connection.promptAck.complete(JsonObject(emptyMap())) }
        fixture.runningTurn(TrustLevel.Full)
        tools.policy = ResolvedToolPolicy(nativeOff = setOf("read"))
        fixture.connection.event(approval("off", "file", "read"))
        runCurrent()
        assertEquals(listOf(answer("off", "confirmed", false)), fixture.connection.sent)
        assertEquals(1, fixture.connections.size)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.session.send(prompt("removed"))
        assertEquals(2, fixture.connections.size)
        assertEquals(emptySet(), fixture.connections.last().plan?.names)
        tools.policy = ResolvedToolPolicy(nativeOn = setOf("read", "grep"))
        assertEquals(2, fixture.connections.size)
        fixture.connections.last().event(record("""{"type":"agent_settled"}"""))
        fixture.session.send(prompt("grown"))
        assertEquals(3, fixture.connections.size)
        assertTrue(fixture.connection.isClosed)
        assertEquals(setOf("read", "grep"), fixture.connections.last().plan?.names)
        assertTrue("switch_session" in fixture.connections.last().commands)
        assertEquals("native", fixture.session.ref.nativeId)
        fixture.session.shutdown()
    }

    @Test
    fun `external turn removes embedded declarations and instructions from an existing process`() = runTest {
        val tools = PlanTools().apply { names = listOf("browser_tabs", "ex_control_active_tab") }
        val bridge = PlanBridge()
        val fixture = fixture(this, tools = tools, bridge = bridge) { _, connection ->
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        fixture.runningTurn()
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        tools.names = listOf("ex_control_active_tab")
        fixture.session.send(prompt("external"))

        assertEquals(2, fixture.connections.size)
        val plan = checkNotNull(fixture.connections.last().plan)
        assertEquals(listOf("ex_control_active_tab"), plan.hosted?.specifications?.map { it.name })
        assertEquals("ex_control_active_tab", plan.hosted?.instructions)
        assertEquals(setOf("ex_control_active_tab"), bridge.scopes.last().declared)
        assertEquals(1, bridge.closed)
        assertTrue("switch_session" in fixture.connections.last().commands)
        assertEquals("native", fixture.session.ref.nativeId)
        fixture.session.shutdown()
    }

    @Test
    fun `external preselection hides embedded tools before native identity exists`() = runTest {
        var externalSession: SessionRef? = null
        val tools = PlanTools().apply {
            scopedNames = { scope ->
                if ("external_browser" in scope.preselected ||
                    (scope.session != null && scope.session == externalSession)
                ) {
                    listOf("ex_control_active_tab")
                } else {
                    listOf("browser_tabs", "ex_control_active_tab")
                }
            }
        }
        val selected = mapOf("external_browser" to setOf("source-browser"))
        val bridge = PlanBridge()
        val fixture = fixture(this, tools = tools, bridge = bridge, preselected = selected) { _, connection ->
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        assertEquals(null, bridge.scopes.single().session)
        assertEquals(selected, bridge.scopes.single().preselected)
        assertEquals(selected, tools.policies.single().preselected)
        assertEquals(setOf("ex_control_active_tab"), bridge.scopes.single().declared)
        externalSession = fixture.session.ref
        fixture.session.send(prompt("external"))

        assertEquals(1, fixture.connections.size)
        assertEquals(externalSession, tools.policies.last().session)
        assertTrue(tools.policies.last().preselected.isEmpty())
        fixture.session.shutdown()
    }

    @Test
    fun `changed browser guidance refreshes the process even when tool names stay the same`() = runTest {
        val tools = PlanTools().apply {
            names = listOf("ex_control_active_tab")
            guidance = "External browser is available"
        }
        val fixture = fixture(this, tools = tools, bridge = PlanBridge()) { _, connection ->
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        tools.guidance = "Every browser reference means the browser hosting this chat"
        fixture.session.send(prompt("external"))

        assertEquals(2, fixture.connections.size)
        assertEquals(tools.guidance, fixture.connections.last().plan?.hosted?.instructions)
        assertEquals(fixture.connection.plan?.names, fixture.connections.last().plan?.names)
        fixture.session.shutdown()
    }

    @Test
    fun `browser origin guidance reaches hooked turns without tools or bridge`() = runTest {
        val tools = PlanTools().apply { guidance = "Every browser reference means the browser hosting this chat" }
        val fixture = fixture(
            this,
            tools = tools,
            project = null,
            areSessionHooksEnabled = true,
        )
        val hosted = checkNotNull(fixture.connection.plan?.hosted)

        assertEquals(tools.guidance, hosted.instructions)
        assertTrue(hosted.specifications.isEmpty())
        assertEquals(null, hosted.endpoint)
        assertEquals(emptySet(), tools.instructions.single().declared)
        fixture.session.shutdown()
        val unhooked = fixture(this, tools = tools, project = null)
        assertEquals(null, unhooked.connection.plan?.hosted)
        unhooked.session.shutdown()
    }

    @Test
    fun `optional bridge attachment failure retains origin guidance without unavailable tool references`() = runTest {
        val source = "Every browser reference means the browser hosting this chat"
        val tools = PlanTools().apply {
            names = listOf("ex_control_active_tab")
            scopedGuidance = { scope ->
                if (scope.declared.orEmpty().isEmpty()) source else "$source; use ex_control_active_tab"
            }
        }
        val bridge = object : AgentToolBridge {
            override val isAvailable = true
            override suspend fun attach(workspace: WorkspaceRef?, context: suspend () -> AgentToolContext?) =
                error("Bridge failed")
        }
        val fixture = fixture(
            this,
            tools = tools,
            bridge = bridge,
            project = null,
            areDetachedToolsEnabled = true,
            areSessionHooksEnabled = true,
        )
        val hosted = checkNotNull(fixture.connection.plan?.hosted)

        assertEquals(source, hosted.instructions)
        assertTrue(hosted.specifications.isEmpty())
        assertEquals(null, hosted.endpoint)
        fixture.session.shutdown()
    }

    @Test
    fun `fresh review removes tools and preserves identity configuration and recovery`() = runTest {
        val tools = PlanTools()
        val fixture = fixture(this, tools = tools, project = null, areSessionHooksEnabled = true) { index, connection ->
            if (index == 0) connection.sessionId = "fresh-review"
            connection.sessionIdAfterSwitch = "fresh-review"
            connection.sessionFile = if (index == 0) "unpersisted-original.jsonl" else "replacement.jsonl"
            connection.isTranscriptPersisted = false
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        val original = fixture.session.ref
        fixture.session.apply("model", SessionConfigurationChange.Model(ModelId("anthropic/reviewer")))
        tools.policy = ResolvedToolPolicy(nativeOff = setOf("read"))

        fixture.session.send(prompt("review").copy(reasoningEffort = "high"))

        assertEquals(2, fixture.connections.size)
        val replacement = fixture.connections.last()
        assertEquals(emptySet(), replacement.plan?.names)
        assertEquals(original.nativeId, replacement.plan?.emptySessionId)
        assertEquals(original, fixture.session.ref)
        assertEquals(original.nativeId, replacement.sessionId)
        assertEquals("reviewer", replacement.model)
        assertEquals("high", replacement.thinkingLevel)
        assertEquals(1, fixture.connection.stopRequests)
        assertFalse("prompt" in fixture.connection.commands)
        assertFalse("switch_session" in replacement.commands)
        assertTrue("prompt" in replacement.commands)
        replacement.event(record("""{"type":"agent_settled"}"""))
        replacement.isTranscriptPersisted = true
        replacement.isOpen = false
        fixture.session.send(prompt("follow-up"))

        val recovered = fixture.connections.last()
        assertEquals(3, fixture.connections.size)
        val switch = recovered.fields[recovered.commands.indexOf("switch_session")]
        assertEquals("replacement.jsonl", switch.string("sessionPath"))
        assertEquals(original, fixture.session.ref)
        fixture.session.shutdown()
    }

    @Test
    fun `fresh restart rejects a changed native identity and safely retries the original identity`() = runTest {
        val tools = PlanTools()
        val fixture = fixture(this, tools = tools) { index, connection ->
            connection.isTranscriptPersisted = false
            connection.promptAck.complete(JsonObject(emptyMap()))
            if (index == 1) connection.sessionId = "wrong-session"
        }
        val original = fixture.session.ref
        tools.policy = ResolvedToolPolicy(nativeOff = setOf("read"))

        val failure = assertFailsWith<EngineException> { fixture.session.send(prompt("refused")) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), failure.failure)
        assertTrue(fixture.connections[1].isClosed)
        assertTrue(fixture.connections.none { "prompt" in it.commands })

        fixture.session.send(prompt("retry"))

        assertEquals(3, fixture.connections.size)
        assertEquals(original, fixture.session.ref)
        assertEquals(original.nativeId, fixture.connections.last().plan?.emptySessionId)
        assertEquals(emptySet(), fixture.connections.last().plan?.names)
        assertTrue("prompt" in fixture.connections.last().commands)
        fixture.session.shutdown()
    }

    @Test
    fun `removed tools after a native prompt without a persisted transcript refuse the next prompt`() = runTest {
        val tools = PlanTools().apply { names = listOf("browser_tabs", "ex_control_active_tab") }
        val fixture = fixture(this, tools = tools, bridge = PlanBridge()) { _, connection ->
            connection.isTranscriptPersisted = false
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        fixture.session.send(prompt("first"))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        tools.names = listOf("ex_control_active_tab")
        val failure = assertFailsWith<EngineException> { fixture.session.send(prompt("external")) }

        assertEquals(EngineFailure.Session(SessionFailureReason.NotResumable), failure.failure)
        assertEquals(1, fixture.connections.size)
        assertFalse(fixture.connection.isClosed)
        assertEquals(1, fixture.connection.commands.count { it == "prompt" })
        fixture.session.shutdown()
    }

    @Test
    fun `resumed session with a missing transcript cannot be recreated as an empty session`() = runTest {
        val tools = PlanTools()
        val reference = SessionRef(PiEngineId, PiSessionSource, "native")
        val fixture = fixture(
            this,
            tools = tools,
            transcript = PiTranscript(reference, "native.jsonl"),
        ) { _, connection ->
            connection.isTranscriptPersisted = false
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        tools.policy = ResolvedToolPolicy(nativeOff = setOf("read"))

        val failure = assertFailsWith<EngineException> { fixture.session.send(prompt("refused")) }

        assertEquals(EngineFailure.Session(SessionFailureReason.NotResumable), failure.failure)
        assertEquals(1, fixture.connections.size)
        assertFalse(fixture.connection.isClosed)
        assertFalse("prompt" in fixture.connection.commands)
        fixture.session.shutdown()
    }

    @Test
    fun `hosted growth rebuilds scoped declarations instructions and capability on the same session`() = runTest {
        val tools = PlanTools()
        val bridge = PlanBridge()
        tools.names = listOf("one")
        val fixture = fixture(this, tools = tools, bridge = bridge) { _, connection ->
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        tools.names = listOf("one", "two")
        fixture.session.send(prompt("more"))
        assertEquals(2, fixture.connections.size)
        val newScope = bridge.scopes.last()
        assertEquals(fixture.session.ref, newScope.session)
        assertEquals(setOf("one", "two"), newScope.declared)
        assertEquals(newScope, tools.instructions.last())
        assertEquals("one,two", fixture.connections.last().plan?.hosted?.instructions)
        assertEquals(1, bridge.closed)
        fixture.session.shutdown()
        assertEquals(2, bridge.closed)
    }

    @Test
    fun `restored process retains selected model when new tools appear`() = runTest {
        val tools = PlanTools()
        val fixture = fixture(this, tools = tools, targetModel = ModelId("anthropic/selected")) { _, connection ->
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        tools.policy = ResolvedToolPolicy(nativeOn = setOf("read", "grep"))
        fixture.session.send(prompt("growth"))
        assertEquals("selected", fixture.connections.last().model)
        assertEquals(ModelId("anthropic/selected"), fixture.session.configuration.value.model)
        fixture.session.shutdown()
    }

    @Test
    fun `unavailable catalog refuses the external prompt before exposing embedded tools`() = runTest {
        val tools = PlanTools().apply { names = listOf("browser_tabs", "ex_control_active_tab") }
        val fixture = fixture(this, tools = tools, bridge = PlanBridge()) { _, connection ->
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        tools.names = listOf("ex_control_active_tab")
        tools.isFailing = true
        assertFailsWith<EngineException> { fixture.session.send(prompt("refused")) }
        assertEquals(1, fixture.connections.size)
        assertFalse("prompt" in fixture.connection.commands)
        assertFalse(fixture.connection.isClosed)
        fixture.session.shutdown()
    }

    @Test
    fun `growth without a persisted transcript is deferred without killing the process`() = runTest {
        val tools = PlanTools()
        val fixture = fixture(this, tools = tools) { _, connection ->
            connection.isTranscriptPersisted = false
            connection.promptAck.complete(JsonObject(emptyMap()))
        }
        tools.policy = ResolvedToolPolicy(nativeOn = setOf("read", "grep"))
        fixture.session.send(prompt("deferred"))
        assertEquals(1, fixture.connections.size)
        assertFalse(fixture.connection.isClosed)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.connection.isTranscriptPersisted = true
        fixture.session.send(prompt("persisted"))
        assertEquals(2, fixture.connections.size)
        fixture.session.shutdown()
    }
}

private class PlanTools : ProfileAgentTools by TestNativeTools {
    var policy = ResolvedToolPolicy(nativeOn = setOf("read"))
    var names = emptyList<String>()
    var scopedNames: ((AgentToolScope) -> List<String>)? = null
    var guidance: String? = null
    var scopedGuidance: ((AgentToolScope) -> String)? = null
    var isFailing = false
    val instructions = mutableListOf<AgentToolScope>()
    val policies = mutableListOf<ToolPolicyScope>()
    override suspend fun nativeTools(scope: ToolPolicyScope): ResolvedToolPolicy {
        policies += scope
        return if (isFailing) error("Catalog unavailable") else policy
    }
    override suspend fun specifications(workspace: WorkspaceRef?) = names.map {
        AgentToolSpec(it, "Tool $it", JsonObject(emptyMap()))
    }
    override suspend fun specifications(scope: AgentToolScope) = scopedNames?.invoke(scope)?.map {
        AgentToolSpec(it, "Tool $it", JsonObject(emptyMap()))
    } ?: specifications(scope.workspace)
    override suspend fun instructions(scope: AgentToolScope): String {
        instructions += scope
        return scopedGuidance?.invoke(scope) ?: guidance ?: scope.declared.orEmpty().sorted().joinToString(",")
    }
    override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict =
        if (call.name in policy.nativeOff) NativeVerdict.Deny("Off") else NativeVerdict.Allow
}

private class PlanBridge : AgentToolBridge {
    override val isAvailable = true
    val scopes = mutableListOf<AgentToolScope>()
    var closed = 0
    override suspend fun attach(workspace: WorkspaceRef?, context: suspend () -> AgentToolContext?) =
        error("Use scoped bridge attachment")
    override suspend fun attach(
        scope: AgentToolScope,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment {
        scopes += scope
        return object : AgentToolBridgeAttachment {
            override val endpoint = AgentToolBridgeEndpoint("http://127.0.0.1:1", "test")
            override fun close() {
                closed++
            }
        }
    }
}
