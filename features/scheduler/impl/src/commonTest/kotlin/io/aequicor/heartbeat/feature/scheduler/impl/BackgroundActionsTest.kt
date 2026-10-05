package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionRecord
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class BackgroundActionsTest {
    private val action = ActionId("a1")
    private val spawn = SpawnRequest(
        SESSION,
        PROJECT,
        TARGET,
        "Helper",
        WakePrompt(RequestId("action_a1"), "task", "x"),
    )

    @Test
    fun `a finished command publishes its result and leaves the journal`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine())
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        assertNull(fixture.actions.startCommand(action, PROJECT, "make test", 5.minutes))
        runCurrent()
        assertEquals(listOf("/work/project" to "make test"), fixture.commands.runs)
        assertEquals(listOf(action), fixture.journal.records.map { it.id })
        fixture.commands.result.complete(CommandOutcome(0, "ok"))
        runCurrent()
        val event = events.single()
        assertEquals(EventKeys.actionFinished(action), event.key)
        assertEquals(EventOrigin.Action(action), event.origin)
        assertEquals("status: exited with code 0\noutput:\nok", event.payload)
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `an unknown project is refused`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine())
        assertEquals(
            "the project is not available",
            fixture.actions.startCommand(action, WorkspaceRef("gone"), "ls", 1.minutes),
        )
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `a helper agent reports when its first turn finishes`() = runTest {
        val host = FakeHost(priority = 1)
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        assertNull(fixture.actions.startAgent(action, spawn))
        assertEquals(listOf(spawn), host.spawned)
        fixture.bus.publish(EventKeys.turnFinished(SESSION), EventOrigin.Host)
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        val result = events.single { it.key == EventKeys.actionFinished(action) }
        assertTrue(result.payload.orEmpty().startsWith("status: finished"), result.payload)
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `no host that creates sessions refuses a helper`() = runTest {
        val host = FakeHost(priority = 1).apply { spawnResult = null }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        assertEquals("no chat host could start a helper agent", fixture.actions.startAgent(action, spawn))
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `interrupted actions wake their sleepers after a restart`() = runTest {
        val sleeper = scheduled("w1", events = setOf(EventKeys.actionFinished(action)))
        val machine = SpecMachine(SchedulerState.Ready(listOf(sleeper)))
        val fixture = ActionsFixture(
            this,
            machine,
            journal = MemoryJournal(
                listOf(ActionRecord(action, "command", START), ActionRecord(ActionId("a2"), "agent", START)),
            ),
        )
        fixture.actions.recover()
        val observed = machine.sent.filterIsInstance<SchedulerIntent.Internal.Observed>().single()
        assertEquals(EventKeys.actionFinished(action), observed.event.key)
        assertTrue(observed.event.payload.orEmpty().startsWith("status: interrupted"))
        assertTrue(fixture.journal.records.isEmpty())
    }
}
