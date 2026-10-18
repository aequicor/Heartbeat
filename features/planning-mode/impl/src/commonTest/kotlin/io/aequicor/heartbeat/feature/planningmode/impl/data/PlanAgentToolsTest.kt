package io.aequicor.heartbeat.feature.planningmode.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanLimits
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningTools
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFileText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanAgentToolsTest {
    private val planning = SpecPlanning(readyWith(plan(text = "# Plan", revision = 2)))
    private val files = MemoryPlanFiles()
    private val toggles = Toggles()
    private val tools = PlanAgentTools(planning, planning, files, toggles, FixedClock)

    @Test
    fun `tools are declared in every session while the feature is on`() = runTest {
        assertEquals(PlanningTools.ALL, tools.specifications(null).map { it.name }.toSet())
        toggles.isEnabled = false
        assertTrue(tools.specifications(null).isEmpty())
        assertEquals("", tools.instructions(AgentToolScope(null)))
    }

    @Test
    fun `tools answer only in sessions bound to an attached plan`() = runTest {
        val other = SessionRef(EngineId("pi"), SessionSourceId("s"), "other")
        assertTrue(tools.execute(context(other), PlanningTools.READ, JsonObject(emptyMap())).isError)
        planning.state.value = readyWith(plan(mode = PlanMode.Detached))
        assertTrue(tools.execute(context(), PlanningTools.READ, JsonObject(emptyMap())).isError)
    }

    @Test
    fun `read returns the plan with its revision`() = runTest {
        val result = tools.execute(context(), PlanningTools.READ, JsonObject(emptyMap()))
        assertFalse(result.isError, result.text)
        assertTrue(result.text.startsWith("Plan revision 2, draft, mode planning (read-only)"), result.text)
        assertTrue(result.text.endsWith("# Plan"))
    }

    @Test
    fun `read picks up an edit made outside the studio`() = runTest {
        files.texts[Chat] = PlanFileText("# Edited", isExternal = true)
        val result = tools.execute(context(), PlanningTools.READ, JsonObject(emptyMap()))
        assertTrue(result.text.startsWith("Plan revision 3"), result.text)
        assertTrue(result.text.endsWith("# Edited"))
        val plan = (planning.state.value as PlanningState.Ready).plans.getValue(Chat)
        assertEquals(PlanAuthor.External, plan.updatedBy)
    }

    @Test
    fun `an external file the plan cannot hold is reported and not taken over`() = runTest {
        files.texts[Chat] = PlanFileText("x".repeat(PlanLimits.MAX_CHARS + 1), isExternal = true)
        val read = tools.execute(context(), PlanningTools.READ, JsonObject(emptyMap()))
        assertTrue(read.isError && "longer than" in read.text && "revision 2" in read.text, read.text)
        val write = tools.execute(context(), PlanningTools.WRITE, args("base_revision" to 2, "content" to "# New"))
        assertTrue(write.isError && "longer than" in write.text, write.text)
        files.texts[Chat] = PlanFileText("", isExternal = true, isOversized = true)
        assertTrue(tools.execute(context(), PlanningTools.READ, JsonObject(emptyMap())).isError)
        assertTrue(planning.sent.isEmpty())
        assertEquals(2, (planning.state.value as PlanningState.Ready).plans.getValue(Chat).revision)
    }

    @Test
    fun `an external file that differs only by a trailing newline is the same plan`() = runTest {
        files.texts[Chat] = PlanFileText("# Plan\n", isExternal = true)
        val read = tools.execute(context(), PlanningTools.READ, JsonObject(emptyMap()))
        assertTrue(read.text.startsWith("Plan revision 2"), read.text)
        assertTrue(planning.sent.isEmpty())
    }

    @Test
    fun `write refuses after an edit outside the studio and takes the edit over`() = runTest {
        files.texts[Chat] = PlanFileText("# Edited", isExternal = true)
        val result = tools.execute(context(), PlanningTools.WRITE, args("base_revision" to 2, "content" to "# New"))
        assertTrue(result.isError && "revision 3" in result.text && PlanningTools.READ in result.text, result.text)
        val plan = (planning.state.value as PlanningState.Ready).plans.getValue(Chat)
        assertEquals("# Edited", plan.text)
        assertEquals(PlanAuthor.External, plan.updatedBy)
    }

    @Test
    fun `a stopped machine is reported apart from a revision conflict`() = runTest {
        planning.isRunning = false
        val write = tools.execute(context(), PlanningTools.WRITE, args("base_revision" to 2, "content" to "# New"))
        assertTrue(write.isError && "not available" in write.text, write.text)
        files.texts[Chat] = PlanFileText("# Edited", isExternal = true)
        val read = tools.execute(context(), PlanningTools.READ, JsonObject(emptyMap()))
        assertTrue(read.isError && "not available" in read.text, read.text)
    }

    @Test
    fun `write from the current revision saves a new one`() = runTest {
        val result = tools.execute(context(), PlanningTools.WRITE, args("base_revision" to 2, "content" to "# New"))
        assertEquals("Plan saved as revision 3.", result.text)
        val plan = (planning.state.value as PlanningState.Ready).plans.getValue(Chat)
        assertEquals("# New", plan.text)
        assertEquals(PlanAuthor.Agent, plan.updatedBy)
    }

    @Test
    fun `write of a section keeps the rest of the plan`() = runTest {
        tools.execute(
            context(),
            PlanningTools.WRITE,
            args("base_revision" to 2, "content" to "1. One", "section" to "## Steps"),
        )
        assertEquals(
            "# Plan\n\n## Steps\n\n1. One",
            (planning.state.value as PlanningState.Ready).plans.getValue(Chat).text,
        )
    }

    @Test
    fun `write from a stale revision or with bad arguments is refused`() = runTest {
        val stale = tools.execute(context(), PlanningTools.WRITE, args("base_revision" to 1, "content" to "# New"))
        assertTrue(stale.isError && "revision 2" in stale.text, stale.text)
        assertTrue(tools.execute(context(), PlanningTools.WRITE, args("content" to "# New")).isError)
        val section = args("base_revision" to 2, "content" to "x", "section" to "Steps")
        assertTrue(tools.execute(context(), PlanningTools.WRITE, section).isError)
        listOf("#Steps", "####### Seven", "#\tTab", "## A\n## B").forEach { heading ->
            val result = tools.execute(
                context(),
                PlanningTools.WRITE,
                args("base_revision" to 2, "content" to "x", "section" to heading),
            )
            assertTrue(result.isError && "markdown heading" in result.text, result.text)
        }
        val huge = args("base_revision" to 2, "content" to "x".repeat(70_000))
        assertTrue(tools.execute(context(), PlanningTools.WRITE, huge).isError)
        assertTrue(planning.sent.isEmpty())
    }

    @Test
    fun `propose marks the current revision for review`() = runTest {
        val result = tools.execute(context(), PlanningTools.PROPOSE, args("revision" to 2, "summary" to "Three steps"))
        assertFalse(result.isError, result.text)
        assertTrue("End your turn" in result.text)
        assertEquals(PlanStatus.Proposed, (planning.state.value as PlanningState.Ready).plans.getValue(Chat).status)
        assertTrue(tools.execute(context(), PlanningTools.PROPOSE, args("revision" to 1, "summary" to "x")).isError)
    }

    @Test
    fun `propose while implementing is refused`() = runTest {
        planning.state.value = readyWith(plan(mode = PlanMode.Implementing, revision = 2))
        assertTrue(tools.execute(context(), PlanningTools.PROPOSE, args("revision" to 2, "summary" to "x")).isError)
    }

    @Test
    fun `engines that rebuild instructions read the plan from them`() = runTest {
        val refreshed = tools.instructions(AgentToolScope(null, session = Session, isRefreshedPerTurn = true))
        assertTrue("Planning mode is on" in refreshed && "# Plan" in refreshed, refreshed)
        val frozen = tools.instructions(AgentToolScope(null, session = Session))
        assertFalse("# Plan" in frozen)
        assertTrue(PlanningTools.READ in frozen)
    }

    private fun context(session: SessionRef = Session) = AgentToolContext(session, null, TurnId("turn"))

    private fun args(vararg values: Pair<String, Any>) = JsonObject(
        values.associate { (key, value) ->
            key to if (value is Int) JsonPrimitive(value) else JsonPrimitive(value.toString())
        },
    )
}
