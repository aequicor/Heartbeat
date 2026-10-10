package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiToolPlanTest {
    @Test
    fun `shrinking live policy denies inspection without restarting and growth waits until the next turn`() = runTest {
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
        fixture.runningTurn()
        assertEquals(1, fixture.connections.size)
        tools.policy = ResolvedToolPolicy(nativeOn = setOf("read", "grep"))
        assertEquals(1, fixture.connections.size)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.session.send(prompt("grown"))
        assertEquals(2, fixture.connections.size)
        assertTrue(fixture.connection.isClosed)
        assertEquals(setOf("read", "grep"), fixture.connections.last().plan?.names)
        assertTrue("switch_session" in fixture.connections.last().commands)
        assertEquals("native", fixture.session.ref.nativeId)
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
    val instructions = mutableListOf<AgentToolScope>()
    override suspend fun nativeTools(scope: ToolPolicyScope) = policy
    override suspend fun specifications(workspace: WorkspaceRef?) = names.map {
        AgentToolSpec(it, "Tool $it", JsonObject(emptyMap()))
    }
    override suspend fun specifications(scope: AgentToolScope) = specifications(scope.workspace)
    override suspend fun instructions(scope: AgentToolScope): String {
        instructions += scope
        return scope.declared.orEmpty().sorted().joinToString(",")
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
