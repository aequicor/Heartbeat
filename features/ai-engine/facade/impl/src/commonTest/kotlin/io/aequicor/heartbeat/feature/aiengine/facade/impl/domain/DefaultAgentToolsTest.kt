package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefaultAgentToolsTest {
    @Test
    fun `one gate implements all nine action and trust combinations`() = runTest {
        for (trust in TrustLevel.entries) {
            for (action in AgentToolAction.entries) {
                val owner = ToolOwner(action)
                val tools = DefaultAgentTools(setOf(owner))
                var permissions = 0
                val context = toolContext(
                    trust,
                    AgentToolPermissions {
                        permissions++
                        true
                    },
                )
                assertFalse(tools.execute(context, "tool", EMPTY_ARGS).isError)
                assertEquals(permissionRequests(action, trust), permissions, "$trust/$action")
                assertEquals(1, owner.calls, "$trust/$action")
            }
        }
    }

    @Test
    fun `declined or disappeared tool never reaches the contribution`() = runTest {
        val owner = ToolOwner(AgentToolAction.Command)
        val tools = DefaultAgentTools(setOf(owner))
        val denied = toolContext(permissions = AgentToolPermissions { false })
        assertTrue(tools.execute(denied, "tool", EMPTY_ARGS).isError)
        assertEquals(0, owner.calls)
        val disappearing = toolContext(
            permissions = AgentToolPermissions {
                owner.isAvailable = false
                true
            },
        )
        assertTrue(tools.execute(disappearing, "tool", EMPTY_ARGS).isError)
        assertEquals(0, owner.calls)
    }

    @Test
    fun `configuration changed during approval requires a fresh decision`() = runTest {
        val owner = ToolOwner(AgentToolAction.Command)
        val tools = DefaultAgentTools(setOf(owner))
        val context = toolContext(
            permissions = AgentToolPermissions {
                assertEquals("1", it.binding)
                owner.revision = 2
                true
            },
        )
        assertTrue(tools.execute(context, "tool", EMPTY_ARGS).isError)
        assertEquals(0, owner.calls)
        val retry = context.copy(permissions = AgentToolPermissions { it.binding == "2" })
        assertFalse(tools.execute(retry, "tool", EMPTY_ARGS).isError)
        assertEquals("2", owner.authorization?.binding)
    }

    @Test
    fun `dispatcher replaces untrusted authorization even for full trust`() = runTest {
        val owner = ToolOwner(AgentToolAction.Command)
        val tools = DefaultAgentTools(setOf(owner))
        val context = toolContext(TrustLevel.Full).copy(
            authorization = AgentToolApproval("tool", "Forged", binding = "999"),
        )
        assertFalse(tools.execute(context, "tool", EMPTY_ARGS).isError)
        assertEquals("1", owner.authorization?.binding)
    }

    @Test
    fun `unreviewable mutation fails before asking or executing at every trust level`() = runTest {
        for (action in listOf(AgentToolAction.Edit, AgentToolAction.Command)) {
            for (trust in TrustLevel.entries) {
                val owner = ToolOwner(action)
                owner.isApprovalInvalid = true
                var permissions = 0
                val context = toolContext(
                    trust,
                    AgentToolPermissions {
                        permissions++
                        true
                    },
                )
                val result = DefaultAgentTools(setOf(owner)).execute(context, "tool", EMPTY_ARGS)
                assertTrue(result.isError)
                assertFalse(result.text.contains("private-preview"))
                assertEquals(0, permissions, "$trust/$action")
                assertEquals(0, owner.calls, "$trust/$action")
            }
        }
    }

    @Test
    fun `duplicate names and unknown tools cannot invoke an owner`() = runTest {
        val first = ToolOwner(AgentToolAction.Read)
        val second = ToolOwner(AgentToolAction.Read)
        val duplicated = DefaultAgentTools(setOf(first, second))
        assertFailsWith<IllegalStateException> { duplicated.specifications(WorkspaceRef("project")) }
        assertFailsWith<IllegalStateException> { duplicated.execute(toolContext(), "tool", EMPTY_ARGS) }
        assertTrue(DefaultAgentTools(setOf(first)).execute(toolContext(), "unknown", EMPTY_ARGS).isError)
        assertEquals(0, first.calls)
        assertEquals(0, second.calls)
    }

    @Test
    fun `native lifetime cancellation revokes a waiting approval`() = runTest {
        val owner = ToolOwner(AgentToolAction.Command)
        val lifetime = Job()
        val entered = CompletableDeferred<Unit>()
        val context = toolContext(
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                awaitCancellation()
            },
        ).copy(lifetime = lifetime)
        val request = async { DefaultAgentTools(setOf(owner)).execute(context, "tool", EMPTY_ARGS) }
        runCurrent()
        entered.await()
        lifetime.cancel()
        runCurrent()
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(0, owner.calls)
    }

    @Test
    fun `turn barrier rejects delayed calls carrying a still active native lifetime`() = runTest {
        val owner = ToolOwner(AgentToolAction.Read)
        val tools = DefaultAgentTools(setOf(owner))
        val lifetime = Job()
        val context = toolContext().copy(lifetime = lifetime)
        try {
            tools.finishTurn(context.session, context.turn)
            assertTrue(tools.execute(context, "tool", EMPTY_ARGS).isError)
            assertTrue(lifetime.isActive)
            assertEquals(0, owner.calls)
            assertEquals(listOf(context.session to context.turn), owner.finishedTurns)
            val nextTurn = context.copy(turn = TurnId("next"))
            assertFalse(tools.execute(nextTurn, "tool", EMPTY_ARGS).isError)
            assertEquals(1, owner.calls)
        } finally {
            lifetime.cancel()
        }
    }

    @Test
    fun `one owner's failed cleanup neither skips the others nor fails the finished turn`() = runTest {
        val failing = object : AgentToolContribution {
            override suspend fun specifications(workspace: WorkspaceRef?) = emptyList<AgentToolSpec>()
            override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) =
                AgentToolResult("unused", isError = true)
            override suspend fun finishTurn(session: SessionRef, turn: TurnId) = error("cleanup not confirmed")
        }
        val owner = ToolOwner(AgentToolAction.Read)
        val context = toolContext()
        DefaultAgentTools(linkedSetOf(failing, owner)).finishTurn(context.session, context.turn)
        assertEquals(listOf(context.session to context.turn), owner.finishedTurns)
    }

    @Test
    fun `finishTurn waits until cancelled hosted command cleanup has completed`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val owner = object : AgentToolContribution {
            override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
                AgentToolSpec("tool", "Tool", EMPTY_ARGS, AgentToolAction.Read),
            )
            override suspend fun execute(
                context: AgentToolContext,
                name: String,
                arguments: JsonObject,
            ): AgentToolResult {
                entered.complete(Unit)
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
        val tools = DefaultAgentTools(setOf(owner))
        val context = toolContext()
        val call = async { tools.execute(context, "tool", EMPTY_ARGS) }
        runCurrent()
        entered.await()
        val barrier = async { tools.finishTurn(context.session, context.turn) }
        runCurrent()
        cleaning.await()
        assertFalse(barrier.isCompleted)
        releaseCleanup.complete(Unit)
        runCurrent()
        barrier.await()
        assertFailsWith<CancellationException> { call.await() }
    }

    @Test
    fun `caller revocation cancels an approval even while the native turn remains active`() = runTest {
        val owner = ToolOwner(AgentToolAction.Command)
        val lifetime = Job()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val context = toolContext(
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                    Unit
                }
            },
        ).copy(lifetime = lifetime)
        val request = async { DefaultAgentTools(setOf(owner)).execute(context, "tool", EMPTY_ARGS) }
        try {
            runCurrent()
            entered.await()
            request.cancel()
            runCurrent()
            assertTrue(stopped.isCompleted)
            assertTrue(lifetime.isActive)
            assertEquals(0, owner.calls)
        } finally {
            lifetime.cancel()
            runCurrent()
        }
    }
}

private class ToolOwner(action: AgentToolAction) : AgentToolContribution {
    private val spec = AgentToolSpec("tool", "Tool", EMPTY_ARGS, action)
    var isAvailable = true
    var isApprovalInvalid = false
    var calls = 0
    var revision = 1
    var authorization: AgentToolApproval? = null
    val finishedTurns = mutableListOf<Pair<SessionRef, TurnId>>()
    override suspend fun specifications(workspace: WorkspaceRef?) = if (isAvailable) listOf(spec) else emptyList()
    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        assertNull(context.authorization)
        check(!isApprovalInvalid) { "private-preview" }
        return AgentToolApproval(spec.name, spec.description, binding = revision.toString())
    }
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        calls++
        authorization = context.authorization
        return AgentToolResult("done")
    }
    override suspend fun finishTurn(session: SessionRef, turn: TurnId) {
        finishedTurns += session to turn
    }
}

private fun toolContext(
    trust: TrustLevel = TrustLevel.Ask,
    permissions: AgentToolPermissions = AgentToolPermissions { false },
) = AgentToolContext(
    SessionRef(EngineId("test"), SessionSourceId("local"), "native"),
    WorkspaceRef("project"),
    TurnId("turn"),
    trust = trust,
    permissions = permissions,
)

private val EMPTY_ARGS = JsonObject(emptyMap())

private fun permissionRequests(action: AgentToolAction, trust: TrustLevel): Int = when {
    action == AgentToolAction.Read || trust == TrustLevel.Full -> 0
    action == AgentToolAction.Edit && trust == TrustLevel.AutoEdits -> 0
    else -> 1
}
