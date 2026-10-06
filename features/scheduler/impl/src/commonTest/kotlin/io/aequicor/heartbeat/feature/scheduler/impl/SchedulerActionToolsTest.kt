package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools.Arguments
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools.Kinds
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.impl.data.SchedulerActionTools
import io.aequicor.heartbeat.feature.scheduler.impl.data.WakeScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerActionToolsTest {
    private val inProject = AgentToolContext(SESSION, PROJECT, TurnId("t1"), target = TARGET)

    private fun TestScope.tools(
        fixture: ActionsFixture,
        toggles: Toggles = Toggles(),
        hosts: Set<ScheduledSessionHost> = setOf(FakeHost(priority = 1, owned = setOf(SESSION))),
    ) = SchedulerActionTools(
        lazyOf(fixture.actions),
        WakeScheduler(fixture.machine, lazyOf(hosts)),
        fixture.machine,
        toggles,
        fixture.clock,
    )

    @Test
    fun `an action with a wake note is not started without an owning host`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine())
        val result = tools(fixture, hosts = emptySet()).execute(
            inProject,
            SchedulerTools.START_ACTION,
            args(Arguments.KIND to Kinds.COMMAND, Arguments.COMMAND to "build", Arguments.WAKE_NOTE to "check it"),
        )
        runCurrent()
        assertTrue(result.isError, result.text)
        assertTrue("no wake was scheduled" in result.text, result.text)
        assertTrue(fixture.machine.sent.isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
    }

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(
        pairs.associate { (k, v) -> k to JsonPrimitive(v) },
    )

    @Test
    fun `the tool is a command for the trust gate and needs both toggles`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine())
        assertEquals(AgentToolAction.Command, tools(fixture).specifications(PROJECT).single().action)
        assertTrue(tools(fixture, Toggles(enabled = false)).specifications(PROJECT).isEmpty())
    }

    @Test
    fun `a command with a wake note schedules the wake before it starts`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine())
        val result = tools(fixture).execute(
            inProject,
            SchedulerTools.START_ACTION,
            args(
                Arguments.KIND to Kinds.COMMAND,
                Arguments.COMMAND to "make test",
                Arguments.WAKE_NOTE to "read results",
            ),
        )
        assertFalse(result.isError, result.text)
        val wake = (fixture.machine.state.value as SchedulerState.Ready).wakes.single()
        val action = fixture.journal.records.single().id
        assertEquals(setOf(EventKeys.actionFinished(action)), wake.request.condition.events)
        assertEquals("read results", wake.request.note)
        assertTrue("end your turn" in result.text)
    }

    @Test
    fun `every start asks the user, and helpers start no helpers`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(FakeHost(priority = 1)))
        val tools = tools(fixture)
        val spec = tools.specifications(PROJECT).single()
        val full = inProject.copy(trust = io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel.Full)
        assertTrue(tools.requiresDecision(full, spec, args(Arguments.KIND to Kinds.COMMAND, Arguments.COMMAND to "ls")))
        val agent = args(Arguments.KIND to Kinds.AGENT, Arguments.PROMPT to "x")
        val started = tools.execute(inProject, SchedulerTools.START_ACTION, agent)
        assertFalse(started.isError, started.text)
        val nested = tools.execute(
            inProject.copy(session = OTHER),
            SchedulerTools.START_ACTION,
            args(Arguments.KIND to Kinds.AGENT, Arguments.PROMPT to "y"),
        )
        assertTrue(nested.isError && "helper" in nested.text, nested.text)
    }

    @Test
    fun `a refused action cancels its wake`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine())
        val helper = tools(fixture).execute(
            inProject,
            SchedulerTools.START_ACTION,
            args(Arguments.KIND to Kinds.AGENT, Arguments.PROMPT to "write docs", Arguments.WAKE_NOTE to "review"),
        )
        assertTrue(helper.isError && "helper" in helper.text, helper.text)
        assertTrue((fixture.machine.state.value as SchedulerState.Ready).wakes.isEmpty())
    }

    @Test
    fun `invalid starts are refused before anything is scheduled`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine(), commands = RecordingCommands(isAvailable = false))
        val tools = tools(fixture)
        val refused = listOf(
            tools.execute(inProject, SchedulerTools.START_ACTION, args(Arguments.KIND to "script")),
            tools.execute(
                inProject,
                SchedulerTools.START_ACTION,
                args(Arguments.KIND to Kinds.COMMAND, Arguments.COMMAND to "ls"),
            ),
            tools.execute(
                inProject.copy(workspace = null),
                SchedulerTools.START_ACTION,
                args(Arguments.KIND to Kinds.COMMAND, Arguments.COMMAND to "ls"),
            ),
            tools.execute(
                inProject.copy(target = null),
                SchedulerTools.START_ACTION,
                args(Arguments.KIND to Kinds.AGENT, Arguments.PROMPT to "x"),
            ),
        )
        assertTrue(refused.all { it.isError }, refused.toString())
        assertTrue((fixture.machine.state.value as SchedulerState.Ready).wakes.isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `cancellation while journaling rolls back wake and reserved slot`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine())
        val tools = tools(fixture)
        val command = args(Arguments.KIND to Kinds.COMMAND, Arguments.COMMAND to "ls", Arguments.WAKE_NOTE to "review")
        fixture.journal.beforeAdd = { awaitCancellation() }
        repeat(3) {
            val caller = async { tools.execute(inProject, SchedulerTools.START_ACTION, command) }
            runCurrent()
            caller.cancelAndJoin()
            assertTrue((fixture.machine.state.value as SchedulerState.Ready).wakes.isEmpty())
        }
        assertTrue(fixture.journal.records.isEmpty())
        fixture.journal.beforeAdd = {}
        repeat(3) {
            val started = tools.execute(inProject, SchedulerTools.START_ACTION, command)
            assertFalse(started.isError, started.text)
        }
    }

    @Test
    fun `failed journal write rolls back the helper wake and reserved slot`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(FakeHost(priority = 1)))
        val tools = tools(fixture)
        val helper = args(Arguments.KIND to Kinds.AGENT, Arguments.PROMPT to "task", Arguments.WAKE_NOTE to "review")
        fixture.journal.beforeAdd = { error("storage unavailable") }
        repeat(3) {
            assertFailsWith<IllegalStateException> { tools.execute(inProject, SchedulerTools.START_ACTION, helper) }
            assertTrue((fixture.machine.state.value as SchedulerState.Ready).wakes.isEmpty())
        }
        fixture.journal.beforeAdd = {}
        val started = tools.execute(inProject, SchedulerTools.START_ACTION, helper)
        assertFalse(started.isError, started.text)
    }
}
