package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DefaultAgentToolsPolicyTest {
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "one")
    private val workspace = WorkspaceRef("project")
    private val context = AgentToolContext(session, workspace, TurnId("turn"), trust = TrustLevel.Full)
    private val scope = AgentToolScope(workspace, session = session)
    private val empty = JsonObject(emptyMap())

    @Test
    fun `session policy filters declarations and instructions and blocks full trust execution`() = runTest {
        val owner = Owner()
        val resolver = ToolPolicyResolver { scope, _, _ ->
            ResolvedToolPolicy(hostedDenied = setOf("edit").takeIf { scope.session == session }.orEmpty())
        }
        val tools = DefaultAgentTools(setOf(owner), policies = resolver)
        assertEquals(listOf("read"), tools.specifications(scope).map { it.name })
        assertEquals("", tools.instructions(scope))
        assertTrue(tools.execute(context, "edit", empty).isError)
        assertEquals(0, owner.executions)
        assertEquals(
            listOf("read", "edit"),
            tools.specifications(scope.copy(session = session.copy(nativeId = "two"))).map { it.name },
        )
        assertFalse(tools.execute(context, "read", empty).isError)
        assertEquals(1, owner.executions)
    }

    @Test
    fun `scoped declarations receive refresh flag and intersect frozen names with policy`() = runTest {
        var received: AgentToolScope? = null
        val owner = object : Owner() {
            override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
                received = scope
                return super.specifications(scope.workspace).filter { scope.declared?.contains(it.name) != false }
            }
        }
        val tools = DefaultAgentTools(
            setOf(owner),
            policies = ToolPolicyResolver { _, _, _ ->
                ResolvedToolPolicy(hostedDenied = setOf("read"))
            },
        )
        val frozen = scope.copy(declared = setOf("read"), isRefreshedPerTurn = true)
        assertTrue(tools.specifications(frozen).isEmpty())
        assertEquals(frozen, received)
        assertEquals("", DefaultAgentTools(setOf(owner)).instructions(frozen))
    }

    @Test
    fun `policy is checked again after permission and a newly disabled tool never executes`() = runTest {
        var denied = emptySet<String>()
        val entered = CompletableDeferred<Unit>()
        val approve = CompletableDeferred<Boolean>()
        val owner = Owner()
        val tools = DefaultAgentTools(
            setOf(owner),
            policies = ToolPolicyResolver { _, _, _ ->
                ResolvedToolPolicy(hostedDenied = denied)
            },
        )
        val call = async {
            tools.execute(
                context.copy(
                    trust = TrustLevel.Ask,
                    permissions = AgentToolPermissions {
                        entered.complete(Unit)
                        approve.await()
                    },
                ),
                "edit",
                empty,
            )
        }
        entered.await()
        denied = setOf("edit")
        approve.complete(true)
        assertTrue(call.await().isError)
        assertEquals(0, owner.executions)
    }

    @Test
    fun `failed execution lookup cannot use fallback declarations before or after approval`() = runTest {
        var isFailed = true
        val owner = Owner()
        val tools = DefaultAgentTools(
            setOf(owner),
            policies = ToolPolicyResolver { _, _, isExecuting ->
                if (isExecuting && isFailed) null else ResolvedToolPolicy()
            },
        )
        assertEquals(2, tools.specifications(scope).size)
        assertTrue(tools.execute(context, "read", empty).isError)
        isFailed = false
        val approval = AgentToolPermissions {
            isFailed = true
            true
        }
        assertTrue(tools.execute(context.copy(trust = TrustLevel.Ask, permissions = approval), "edit", empty).isError)
        assertEquals(0, owner.executions)
    }

    @Test
    fun `policy lookup receives bound target and trusted session instead of model arguments`() = runTest {
        val observed = mutableListOf<ToolPolicyScope>()
        val tools = DefaultAgentTools(
            setOf(Owner()),
            policies = ToolPolicyResolver { scope, _, _ ->
                observed += scope
                ResolvedToolPolicy()
            },
        )
        val target = EngineTarget(session.engine, EngineBindingId("binding"), ModelId("model"))
        val request = RequestId("request")
        tools.bindTurn(session, request, TurnId("facade"), target)
        assertFalse(tools.execute(context.copy(request = request), "read", empty).isError)
        assertEquals(setOf(ToolPolicyScope(session.engine, workspace, session, target)), observed.toSet())
    }

    private open class Owner : AgentToolContribution {
        var executions = 0
        override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
            AgentToolSpec("read", "Read", JsonObject(emptyMap())),
            AgentToolSpec("edit", "Edit", JsonObject(emptyMap()), AgentToolAction.Edit),
        )
        override suspend fun instructions(workspace: WorkspaceRef?) = "Use read and edit"
        override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
            executions++
            return AgentToolResult("done")
        }
    }
}
