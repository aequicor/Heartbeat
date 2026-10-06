package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiHostedSessionTest {
    @Test
    fun `image prompt keeps original history while hosted tools share its lifetime`() = runTest {
        val bridge = HostedBridge()
        val fixture = fixture(
            this,
            tools = HostedToolDeclarations,
            bridge = bridge,
            resources = ResourceResolver { ResolvedResource("image.png", "image/png", byteArrayOf(1)) },
        ) { _, connection ->
            connection.modelMetadata = record("""{"input":["text","image"]}""")
        }
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val parts = listOf(ContentPart.Image(ResourceRef("attachment:image", "image/png")))
        val turn = fixture.session.send(PromptRequest(RequestId("image"), parts, trust = TrustLevel.Full))
        assertEquals(turn, requireNotNull(bridge.context()).turn)
        val native = fixture.connection.fields[fixture.connection.commands.indexOf("prompt")]
        assertEquals(1, native.getValue("images").jsonArray.size)
        fixture.connection.event(
            record("""{"type":"message_end","message":{"role":"user","timestamp":42,"content":""}}"""),
        )
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(
            fixture.session.features.resolve(SessionHistory),
        ).feature.page()
        assertEquals(parts, history.items.filterIsInstance<SessionItem.Message>().last().parts)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertEquals(null, bridge.context())
        fixture.session.shutdown()
    }

    @Test
    fun `session without a project starts with hosted tools only when its caller opted in`() = runTest {
        val plainBridge = HostedBridge()
        fixture(this, tools = HostedToolDeclarations, bridge = plainBridge, project = null).session.shutdown()
        assertTrue(plainBridge.attached.isEmpty())

        val bridge = HostedBridge()
        val fixture = fixture(
            this,
            tools = HostedToolDeclarations,
            bridge = bridge,
            project = null,
            areDetachedToolsEnabled = true,
        )
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val turn = fixture.session.send(PromptRequest(RequestId("chat"), listOf(ContentPart.Text("Hi"))))
        val context = requireNotNull(bridge.context())
        assertEquals(listOf<WorkspaceRef?>(null), bridge.attached)
        assertEquals(null, context.workspace)
        assertEquals(turn, context.turn)
        assertEquals(ModelId("anthropic/test"), context.target?.model)
        fixture.session.shutdown()
    }

    @Test
    fun `chat without a project starts without detached tools where no bridge exists`() = runTest {
        val fixture = fixture(this, tools = HostedToolDeclarations, project = null, areDetachedToolsEnabled = true)
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(PromptRequest(RequestId("chat"), listOf(ContentPart.Text("Hi"))))
        fixture.session.shutdown()
    }

    @Test
    fun `chat without a project starts without detached tools when they fail`() = runTest {
        val failing = object : AgentToolBridge {
            override val isAvailable = true
            override suspend fun attach(
                workspace: WorkspaceRef?,
                context: suspend () -> AgentToolContext?,
            ): AgentToolBridgeAttachment = error("Bridge failed")
        }
        val failingInstructions = object : ProfileAgentTools by HostedToolDeclarations {
            override suspend fun instructions(scope: AgentToolScope): String = error("Contribution failed")
        }
        val bridge = HostedBridge()
        listOf(HostedToolDeclarations to failing, failingInstructions to bridge).forEach { (tools, toolBridge) ->
            val fixture = fixture(
                this,
                tools = tools,
                bridge = toolBridge,
                project = null,
                areDetachedToolsEnabled = true,
            )
            assertNull(fixture.connection.plan?.hosted)
            fixture.connection.promptAck.complete(JsonObject(emptyMap()))
            fixture.session.send(PromptRequest(RequestId("chat"), listOf(ContentPart.Text("Hi"))))
            fixture.session.shutdown()
        }
        assertTrue(bridge.attached.isEmpty())
    }

    @Test
    fun `live trust changes apply to future hosted calls while an approval keeps waiting`() = runTest {
        val bridge = HostedBridge()
        val fixture = fixture(this, tools = HostedToolDeclarations, bridge = bridge)
        fixture.runningTurn(TrustLevel.Ask)
        val before = requireNotNull(bridge.context())
        val pending = CoroutineScope(coroutineContext + requireNotNull(before.lifetime)).async {
            before.permissions.request(AgentToolApproval("run_build", "Build"))
        }
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        fixture.session.apply("live-trust", SessionConfigurationChange.Trust(TrustLevel.Full))
        assertEquals(TrustLevel.Ask, before.trust)
        assertEquals(TrustLevel.Full, requireNotNull(bridge.context()).trust)
        assertTrue(pending.isActive)
        assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        runCurrent()
        assertTrue(pending.isCancelled)
        fixture.session.shutdown()
    }

    @Test
    fun `native process loss revokes a hosted permission and its execution identity`() = runTest {
        val bridge = HostedBridge()
        val fixture = fixture(this, tools = HostedToolDeclarations, bridge = bridge)
        fixture.runningTurn(TrustLevel.Ask)
        val context = requireNotNull(bridge.context())
        val pending = CoroutineScope(coroutineContext + requireNotNull(context.lifetime)).async {
            context.permissions.request(AgentToolApproval("run_build", "Build"))
        }
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(null, bridge.context())
        fixture.session.shutdown()
        assertTrue(bridge.isClosed)
    }

    @Test
    fun `hosted permission uses the accepted turn and is cancelled when native turn ends`() = runTest {
        val bridge = HostedBridge()
        val fixture = fixture(this, tools = HostedToolDeclarations, bridge = bridge)
        assertEquals(null, bridge.context())
        val turn = fixture.runningTurn(TrustLevel.AutoEdits)
        val context = requireNotNull(bridge.context())
        assertEquals(turn, context.turn)
        assertEquals(TrustLevel.AutoEdits, context.trust)
        assertEquals(fixture.session.ref, context.session)
        val toolScope = CoroutineScope(coroutineContext + requireNotNull(context.lifetime))
        val answer = toolScope.async {
            context.permissions.request(AgentToolApproval("run_build", "Build project"))
        }
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests.single()
        fixture.session.respond(PermissionDecision(turn, request.id, PermissionOptionId("hosted.allow")))
        assertTrue(answer.await())
        assertTrue(fixture.connection.sent.isEmpty())
        val pending = toolScope.async {
            context.permissions.request(AgentToolApproval("run_build", "Build again"))
        }
        runCurrent()
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(null, bridge.context())
        fixture.session.close()
        assertTrue(bridge.isClosed)
    }

    private suspend fun Fixture.runningTurn(trust: TrustLevel): TurnId {
        connection.promptAck.complete(JsonObject(emptyMap()))
        val prompt = PromptRequest(RequestId("hosted"), listOf(ContentPart.Text("Build")), trust = trust)
        val accepted = session.send(prompt)
        connection.event(record("""{"type":"agent_start"}"""))
        return accepted
    }
    private fun record(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}

private object HostedToolDeclarations : ProfileAgentTools {
    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        listOf(AgentToolSpec("run_build", "Run a build", JsonObject(emptyMap())))
    override suspend fun instructions(workspace: WorkspaceRef?): String = "Use run_build"
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        error("This fixture exercises the session permission callback")
}

private class HostedBridge : AgentToolBridge {
    override val isAvailable = true
    var context: suspend () -> AgentToolContext? = { null }
    var isClosed = false
    val attached = mutableListOf<WorkspaceRef?>()
    override suspend fun attach(
        workspace: WorkspaceRef?,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment {
        attached += workspace
        this.context = context
        return object : AgentToolBridgeAttachment {
            override val endpoint = AgentToolBridgeEndpoint("http://127.0.0.1:1", "fixture")
            override fun close() {
                isClosed = true
            }
        }
    }
}
