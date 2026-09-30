package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostedTurnIdentityTest {
    @Test
    fun `hosted identity is canonical before and after native send acknowledgement`() = runTest {
        for (isEarly in listOf(true, false)) {
            val owner = HostedIdentityOwner()
            val tools = DefaultAgentTools(setOf(owner))
            val native = FakeNativeSession()
            val session = open(native, tools)
            var permissionTurn: TurnId? = null
            fun context(turn: Turn) = AgentToolContext(
                native.ref,
                WorkspaceRef("project"),
                turn.id,
                turn.request,
                permissions = AgentToolPermissions {
                    permissionTurn = native.activeTurn.id
                    true
                },
            )
            if (isEarly) native.onAccepted = { tools.execute(context(it), "tool", EMPTY_ARGUMENTS) }
            val facadeTurn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Run"))))
            val nativeTurn = native.activeTurn
            if (!isEarly) tools.execute(context(nativeTurn), "tool", EMPTY_ARGUMENTS)
            assertNotEquals(nativeTurn.id, facadeTurn)
            assertEquals(facadeTurn, owner.contexts.single().turn)
            assertEquals(nativeTurn.request, owner.contexts.single().request)
            assertEquals(nativeTurn.id, permissionTurn)
            native.finish()
            runCurrent()
            assertEquals(facadeTurn, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.id)
            tools.finishTurn(session.ref, facadeTurn)
            assertTrue(tools.execute(context(nativeTurn), "tool", EMPTY_ARGUMENTS).isError)
            assertEquals(1, owner.contexts.size)
        }
    }

    @Test
    fun `facade barrier revokes a permission callback still addressed to the native turn`() = runTest {
        val owner = HostedIdentityOwner()
        val tools = DefaultAgentTools(setOf(owner))
        val native = FakeNativeSession()
        val session = open(native, tools)
        val facadeTurn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Run"))))
        val nativeTurn = native.activeTurn
        val waiting = CompletableDeferred<TurnId>()
        val context = AgentToolContext(
            native.ref,
            WorkspaceRef("project"),
            nativeTurn.id,
            nativeTurn.request,
            permissions = AgentToolPermissions {
                waiting.complete(native.activeTurn.id)
                awaitCancellation()
            },
        )
        val call = backgroundScope.async { tools.execute(context, "tool", EMPTY_ARGUMENTS) }
        runCurrent()
        assertEquals(nativeTurn.id, waiting.await())
        tools.finishTurn(session.ref, facadeTurn)
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(owner.contexts.isEmpty())
        assertTrue(native.state.value is ActiveSessionState.Running)
    }

    @Test
    fun `facade terminal barrier cancels native calls and waits for their cleanup`() = runTest {
        val owner = HostedIdentityOwner(isBlocking = true)
        val tools = DefaultAgentTools(setOf(owner))
        val native = FakeNativeSession()
        val session = open(native, tools)
        val facadeTurn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Run"))))
        val nativeTurn = native.activeTurn
        val context = AgentToolContext(
            native.ref,
            WorkspaceRef("project"),
            nativeTurn.id,
            nativeTurn.request,
            permissions = AgentToolPermissions { true },
        )
        val call = backgroundScope.async { tools.execute(context, "tool", EMPTY_ARGUMENTS) }
        runCurrent()
        owner.entered.await()
        native.finish()
        runCurrent()
        val barrier = async { tools.finishTurn(session.ref, facadeTurn) }
        runCurrent()
        owner.cleaning.await()
        assertFalse(barrier.isCompleted)
        assertNotEquals(nativeTurn.id, facadeTurn)
        assertEquals(facadeTurn, owner.contexts.single().turn)
        owner.releaseCleanup.complete(Unit)
        barrier.await()
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(tools.execute(context, "tool", EMPTY_ARGUMENTS).isError)
    }

    private fun TestScope.open(native: FakeNativeSession, tools: DefaultAgentTools): ActiveSession {
        val fixture = RouteFixture(this)
        val policy = SessionPolicy(
            fixture.routes,
            EnabledEngines(fixture.registry, fixture.toggles, backgroundScope),
            ActiveSessionRegistry(),
            fixture.context,
            tools,
        )
        val route = ExecutionRoute(TestEngine, fixture.binding.id, fixture.source.info.id, fixture.source.info.revision)
        return ActiveSessionAssembler(policy, backgroundScope)
            .assemble(native, route, ModelId("m1"), TestHandleScope("hosted", backgroundScope))
            .also { runCurrent() }
    }

    private fun ActiveSession.sender() = (features.resolve(SendsPrompts) as FeatureAccess.Available).feature
}

private class HostedIdentityOwner(private val isBlocking: Boolean = false) : AgentToolContribution {
    val contexts = mutableListOf<AgentToolContext>()
    val entered = CompletableDeferred<Unit>()
    val cleaning = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
        AgentToolSpec("tool", "Tool", EMPTY_ARGUMENTS, AgentToolAction.Command),
    )
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        contexts += context
        entered.complete(Unit)
        if (!isBlocking) return AgentToolResult("done")
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                cleaning.complete(Unit)
                releaseCleanup.await()
            }
        }
    }
}

private val EMPTY_ARGUMENTS = JsonObject(emptyMap())
