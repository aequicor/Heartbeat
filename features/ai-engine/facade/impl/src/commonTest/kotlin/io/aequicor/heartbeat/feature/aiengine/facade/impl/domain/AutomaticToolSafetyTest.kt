package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticToolSafetyTest {
    @Test
    fun `reviewed operations execute without permission at every trust level and action`() = runTest {
        for (trust in TrustLevel.entries) {
            for (action in AgentToolAction.entries) {
                val owner = SafetyOwner(action)
                val tools = DefaultAgentTools(setOf(owner))
                val context = safetyContext(trust)
                assertFalse(tools.execute(context, "mini_app", EMPTY).isError, "$trust/$action")
                assertEquals(1, owner.calls)
                assertEquals(owner.snapshot(), owner.authorization)
                assertEquals(trust, context.trust)
            }
        }
    }

    @Test
    fun `risk uncertainty and unavailable review cannot be bypassed by full trust or prior authorization`() = runTest {
        for (reason in listOf("Risk: destructive write", "Uncertain: unknown API", "Review unavailable")) {
            for (trust in TrustLevel.entries) {
                val owner = SafetyOwner().apply {
                    existing = snapshot()
                    assessment = { AgentToolAutomaticAuthorization.Denied(reason) }
                }
                val result = DefaultAgentTools(setOf(owner)).execute(safetyContext(trust), "mini_app", EMPTY)
                assertTrue(result.isError)
                assertEquals(reason, result.text)
                assertEquals(0, owner.calls)
            }
        }
    }

    @Test
    fun `automatic acceptance requires exact nonblank snapshot binding`() = runTest {
        for (approved in listOf(
            AgentToolApproval("mini_app", "Mini app"),
            AgentToolApproval("mini_app", "Mini app", binding = " "),
            AgentToolApproval("mini_app", "Mini app", binding = "previous"),
            AgentToolApproval("mini_app", "Different operation", binding = "revision-1"),
        )) {
            val owner = SafetyOwner().apply { assessment = { AgentToolAutomaticAuthorization.Allowed(approved) } }
            assertTrue(DefaultAgentTools(setOf(owner)).execute(safetyContext(), "mini_app", EMPTY).isError)
            assertEquals(0, owner.calls)
        }
    }

    @Test
    fun `changed source or disappearance while review waits refuses the operation`() = runTest {
        for (disappears in listOf(false, true)) {
            val owner = SafetyOwner()
            owner.assessment = {
                val reviewed = owner.snapshot()
                if (disappears) owner.isAvailable = false else owner.revision++
                AgentToolAutomaticAuthorization.Allowed(reviewed)
            }
            val result = DefaultAgentTools(setOf(owner)).execute(safetyContext(), "mini_app", EMPTY)
            assertTrue(result.isError)
            assertEquals(0, owner.calls)
        }
    }

    @Test
    fun `hooks and policies remain binding before and after the independent review`() = runTest {
        for (verdict in listOf(ToolHookVerdict.Ask("Needs confirmation"), ToolHookVerdict.Deny("Forbidden"))) {
            val owner = SafetyOwner()
            val hooks = SafetyHooks().apply { current = verdict }
            assertTrue(DefaultAgentTools(setOf(owner), hooks).execute(safetyContext(), "mini_app", EMPTY).isError)
            assertEquals(0, owner.calls)

            hooks.current = ToolHookVerdict.Continue
            owner.assessment = {
                hooks.current = verdict
                AgentToolAutomaticAuthorization.Allowed(owner.snapshot())
            }
            assertTrue(DefaultAgentTools(setOf(owner), hooks).execute(safetyContext(), "mini_app", EMPTY).isError)
            assertEquals(0, owner.calls)
        }
        var isOff = false
        val owner = SafetyOwner()
        owner.assessment = {
            isOff = true
            AgentToolAutomaticAuthorization.Allowed(owner.snapshot())
        }
        val tools = DefaultAgentTools(
            setOf(owner),
            policies = ToolPolicyResolver { _, _, _ ->
                ResolvedToolPolicy(hostedDenied = if (isOff) setOf("mini_app") else emptySet())
            },
        )
        assertTrue(tools.execute(safetyContext(), "mini_app", EMPTY).isError)
        assertEquals(0, owner.calls)
    }

    @Test
    fun `review failures are sanitized and never fall back to a permission dialog`() = runTest {
        val owner = SafetyOwner().apply { assessment = { error("private source and secrets") } }
        val result = DefaultAgentTools(setOf(owner)).execute(safetyContext(), "mini_app", EMPTY)
        assertTrue(result.isError)
        assertFalse(result.text.contains("private source"))
        assertEquals(0, owner.calls)
    }

    @Test
    fun `revoking the native turn cancels review before any execution`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val lifetime = Job()
        val owner = SafetyOwner().apply {
            assessment = {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val tools = DefaultAgentTools(setOf(owner))
        val task = async { tools.execute(safetyContext().copy(lifetime = lifetime), "mini_app", EMPTY) }
        entered.await()
        lifetime.cancel()
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(0, owner.calls)
    }

    @Test
    fun `opting in one contribution does not suppress ordinary tool permissions`() = runTest {
        val owner = SafetyOwner().apply { assessment = { null } }
        var permissions = 0
        val context = safetyContext().copy(
            permissions = AgentToolPermissions {
                permissions++
                false
            },
        )
        assertTrue(DefaultAgentTools(setOf(owner)).execute(context, "mini_app", EMPTY).isError)
        assertEquals(1, permissions)
        assertEquals(0, owner.calls)
    }
}

private class SafetyOwner(action: AgentToolAction = AgentToolAction.Command) : AgentToolContribution {
    private val spec = AgentToolSpec("mini_app", "Mini app", EMPTY, action)
    var isAvailable = true
    var revision = 1
    var calls = 0
    var authorization: AgentToolApproval? = null
    var existing: AgentToolApproval? = null
    var assessment: suspend () -> AgentToolAutomaticAuthorization? = {
        AgentToolAutomaticAuthorization.Allowed(snapshot())
    }
    fun snapshot() = AgentToolApproval(spec.name, spec.description, binding = "revision-$revision")
    override suspend fun specifications(workspace: WorkspaceRef?) = if (isAvailable) listOf(spec) else emptyList()
    override suspend fun requiresDecision(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject) = true
    override suspend fun approval(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject) = snapshot()
    override suspend fun existingAuthorization(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject) =
        existing
    override suspend fun automaticAuthorization(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolAutomaticAuthorization? = assessment()
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        calls++
        authorization = context.authorization
        return AgentToolResult("saved")
    }
}

private class SafetyHooks : SessionHooks {
    var current: ToolHookVerdict = ToolHookVerdict.Continue
    override fun context(session: SessionRef, request: RequestId?, turn: TurnId?) =
        SessionHookContext(session, null, request, turn, SessionOwner("test"))
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = current
}

private fun safetyContext(trust: TrustLevel = TrustLevel.Ask) = AgentToolContext(
    SessionRef(EngineId("test"), SessionSourceId("local"), "caller"),
    WorkspaceRef("workspace"),
    TurnId("turn"),
    trust = trust,
    permissions = AgentToolPermissions { throw AssertionError("Automatic authorization must never ask the user") },
)

private val EMPTY = JsonObject(emptyMap())
