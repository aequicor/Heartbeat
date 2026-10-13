package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticModeHostedTest {
    @Test
    fun `unreviewed hosted mutations refuse every trust level without permission fallback`() = runTest {
        for (action in listOf(AgentToolAction.Edit, AgentToolAction.Command)) {
            for (trust in TrustLevel.entries) {
                val owner = LegacyOwner(action)
                val tools = DefaultAgentTools(setOf(owner))
                assertTrue(tools.execute(Context.copy(trust = trust), "tool", Empty).isError)
                assertEquals(0, owner.calls)
            }
        }
    }

    @Test
    fun `plain hosted read remains available without independent side effect review`() = runTest {
        val owner = LegacyOwner(AgentToolAction.Read)
        assertFalse(DefaultAgentTools(setOf(owner)).execute(Context, "tool", Empty).isError)
        assertEquals(1, owner.calls)
    }

    @Test
    fun `owner decision requirement also refuses reads without a permission dialog`() = runTest {
        for (isLate in listOf(false, true)) {
            var checks = 0
            val owner = LegacyOwner(AgentToolAction.Read).apply { requires = { ++checks > if (isLate) 1 else 0 } }
            assertTrue(DefaultAgentTools(setOf(owner)).execute(Context, "tool", Empty).isError)
            assertEquals(0, owner.calls)
        }
    }

    @Test
    fun `initial and late ask hooks cannot turn an automatic read into a user question`() = runTest {
        for (isLate in listOf(false, true)) {
            var calls = 0
            val hooks = object : SessionHooks {
                override fun context(session: SessionRef, request: RequestId?, turn: TurnId?) =
                    SessionHookContext(session, Context.workspace, request, turn, SessionOwner("test"))
                override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict =
                    if (++calls > if (isLate) 1 else 0) ToolHookVerdict.Ask("Check") else ToolHookVerdict.Continue
            }
            val owner = LegacyOwner(AgentToolAction.Read)
            assertTrue(DefaultAgentTools(setOf(owner), hooks).execute(Context, "tool", Empty).isError)
            assertEquals(0, owner.calls)
        }
    }
}

private class LegacyOwner(private val action: AgentToolAction) : AgentToolContribution {
    var calls = 0
    var requires: () -> Boolean = { false }
    override suspend fun specifications(workspace: WorkspaceRef?) = listOf(AgentToolSpec("tool", "Tool", Empty, action))
    override suspend fun requiresDecision(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject) =
        requires()
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        calls++
        return AgentToolResult("done")
    }
}

private val Empty = JsonObject(emptyMap())
private val Context = AgentToolContext(
    SessionRef(EngineId("test"), SessionSourceId("local"), "caller"),
    WorkspaceRef("workspace"),
    TurnId("turn"),
    permissions = AgentToolPermissions { throw AssertionError("Automatic safety must never request permission") },
    decisionMode = AgentToolDecisionMode.AutomaticSafety,
)
