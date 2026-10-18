package io.aequicor.heartbeat.feature.planningmode.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanContextHookTest {
    private val planning = SpecPlanning(readyWith(plan(text = "# Plan", revision = 1)))
    private var isEnabled = true
    private var isRefreshed = false
    private val hook = PlanContextHook(planning, { isEnabled }) { isRefreshed }
    private val context = SessionHookContext(Session, null, null, null, SessionOwner("owner"))

    @Test
    fun `plan is delivered once per context revision`() = runTest {
        val addition = assertNotNull(hook.preparePrompt(context, "hello", "r1"))
        assertTrue("<plan>\n# Plan\n</plan>" in addition.text)
        assertNotNull(hook.preparePrompt(context, "again", "r1"), "not accepted yet")
        assertNotNull(addition.receipt).accepted(context, "r1")
        assertNull(hook.preparePrompt(context, "again", "r1"))
        assertNotNull(hook.preparePrompt(context, "after compaction", "r2"))
    }

    @Test
    fun `a new revision status or mode is delivered again`() = runTest {
        assertNotNull(hook.preparePrompt(context, "hello", "r1")?.receipt).accepted(context, "r1")
        planning.state.value = readyWith(plan(text = "# Plan", revision = 1).copy(status = PlanStatus.Approved))
        assertTrue("approved by the user" in assertNotNull(hook.preparePrompt(context, "x", "r1")).text)
        planning.state.value = readyWith(plan(mode = PlanMode.Implementing, text = "# Plan", revision = 1))
        assertTrue("implements the plan" in assertNotNull(hook.preparePrompt(context, "x", "r1")).text)
    }

    @Test
    fun `without a context revision the plan accompanies every prompt`() = runTest {
        assertNotNull(hook.preparePrompt(context, "hello", null)?.receipt).accepted(context, null)
        assertNotNull(hook.preparePrompt(context, "again", null))
    }

    @Test
    fun `nothing is delivered for commands detached plans refreshed engines or a disabled feature`() = runTest {
        assertNull(hook.preparePrompt(context, "/compact", "r1"))
        isRefreshed = true
        assertNull(hook.preparePrompt(context, "hello", "r1"))
        isRefreshed = false
        isEnabled = false
        assertNull(hook.preparePrompt(context, "hello", "r1"))
        isEnabled = true
        planning.state.value = readyWith(plan(mode = PlanMode.Detached))
        assertNull(hook.preparePrompt(context, "hello", "r1"))
    }

    @Test
    fun `a user edit during a turn is announced once in the next tool result`() = runTest {
        assertNotNull(hook.preparePrompt(context, "hello", "r1")?.receipt).accepted(context, "r1")
        assertNull(hook.afterTool(call("Read"), AgentToolResult("ok")))
        planning.state.value = readyWith(plan(text = "# Edited", revision = 2).copy(updatedBy = PlanAuthor.User))
        val note = assertNotNull(hook.afterTool(call("Read"), AgentToolResult("ok")))
        assertTrue("revision 2" in note)
        assertNull(hook.afterTool(call("Read"), AgentToolResult("ok")))
    }

    @Test
    fun `the agent's own writes and re-reads are not announced`() = runTest {
        assertNotNull(hook.preparePrompt(context, "hello", "r1")?.receipt).accepted(context, "r1")
        planning.state.value = readyWith(plan(text = "# Mine", revision = 2).copy(updatedBy = PlanAuthor.Agent))
        assertNull(hook.afterTool(call("plan_write"), AgentToolResult("ok")))
        planning.state.value = readyWith(plan(text = "# User", revision = 3).copy(updatedBy = PlanAuthor.User))
        assertNull(hook.afterTool(call("mcp__heartbeat_tools__plan_read"), AgentToolResult("ok")))
        assertNull(hook.afterTool(call("Read"), AgentToolResult("ok")))
        assertEquals(null, hook.afterTool(call("Grep"), AgentToolResult("ok")))
    }

    @Test
    fun `a plan_read of another MCP server is not the plan tool`() = runTest {
        assertNotNull(hook.preparePrompt(context, "hello", "r1")?.receipt).accepted(context, "r1")
        planning.state.value = readyWith(plan(text = "# User", revision = 2).copy(updatedBy = PlanAuthor.User))
        assertNotNull(hook.afterTool(call("mcp__other__plan_read"), AgentToolResult("ok")))
    }

    @Test
    fun `sessions that no longer serve a plan are forgotten`() = runTest {
        val other = SessionRef(EngineId("pi"), SessionSourceId("s"), "other")
        val otherContext = SessionHookContext(other, null, null, null, SessionOwner("owner"))
        assertNotNull(hook.preparePrompt(context, "hello", "r1")?.receipt).accepted(context, "r1")
        assertEquals(setOf(Session), hook.rememberedSessions)
        planning.state.value = PlanningState.Ready(
            mapOf(Chat to plan(text = "# Plan", revision = 1)),
            mapOf(other to Chat),
        )
        assertNotNull(hook.preparePrompt(otherContext, "hello", "r1")?.receipt).accepted(otherContext, "r1")
        hook.afterTool(HookedToolCall(otherContext, "Read", AgentToolAction.Read, JsonObject(emptyMap())), ok)
        assertEquals(setOf(other), hook.rememberedSessions)
        planning.state.value = PlanningState.Ready(mapOf(Chat to plan(text = "# Plan", revision = 1)))
        assertNull(hook.afterTool(call("Read"), ok))
        assertEquals(emptySet(), hook.rememberedSessions)
    }

    private val ok = AgentToolResult("ok")

    private fun call(name: String) = HookedToolCall(context, name, AgentToolAction.Read, JsonObject(emptyMap()))
}
