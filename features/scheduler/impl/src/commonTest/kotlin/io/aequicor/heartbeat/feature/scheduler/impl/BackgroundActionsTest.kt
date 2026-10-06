package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionRecord
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertNull(fixture.actions.startCommand(action, SESSION, PROJECT, "make test", 5.minutes))
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
            fixture.actions.startCommand(action, SESSION, WorkspaceRef("gone"), "ls", 1.minutes),
        )
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `a helper agent reports when its first turn finishes`() = runTest {
        val host = HelperHostFake()
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        assertNull(fixture.actions.startAgent(action, spawn))
        assertEquals(listOf(spawn.prompt.request), host.prompts.map { it.request })
        host.results[spawn.prompt.request] = HelperResult(spawn.prompt.request, HelperOutcome.Completed, "done")
        fixture.bus.publish(EventKeys.turnFinished(SESSION), EventOrigin.Host)
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        val result = events.single { it.key == EventKeys.actionFinished(action) }
        assertTrue(result.payload.orEmpty().startsWith("status: finished"), result.payload)
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `running actions are limited per session and the helper is marked`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(HelperHostFake()))
        repeat(3) { assertNull(fixture.actions.startCommand(ActionId("c$it"), SESSION, PROJECT, "sleep 1", 1.minutes)) }
        val refused = fixture.actions.startCommand(ActionId("c4"), SESSION, PROJECT, "sleep 1", 1.minutes)
        assertTrue(refused.orEmpty().contains("this session"), refused)
        assertNull(fixture.actions.startAgent(ActionId("h1"), spawn.copy(parent = OTHER)))
        assertTrue(fixture.actions.isHelper(OTHER))
        assertFalse(fixture.actions.isHelper(SESSION))
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
                listOf(ActionRecord(action, "command", START)),
            ),
        )
        fixture.actions.recover()
        val observed = machine.sent.filterIsInstance<SchedulerIntent.Internal.Observed>().single()
        assertEquals(EventKeys.actionFinished(action), observed.event.key)
        assertTrue(observed.event.payload.orEmpty().startsWith("status: interrupted"))
        assertEquals(listOf(action), fixture.journal.records.map { it.id })
        machine.send(SchedulerIntent.Internal.Delivered(sleeper.id, WakeReason.Event(observed.event)))
        fixture.persistWakes()
        fixture.actions.recover()
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `completed result survives reopening while the parent is busy`() = runTest {
        val sleeper = scheduled("w1", events = setOf(EventKeys.actionFinished(action)))
        val first = ActionsFixture(this, SpecMachine(SchedulerState.Ready(listOf(sleeper))))
        first.actions.startCommand(action, SESSION, PROJECT, "make test", 5.minutes)
        first.commands.result.complete(CommandOutcome(0, "all tests passed"))
        runCurrent()
        val completed = first.journal.records.single()
        assertTrue(completed.payload.orEmpty().contains("all tests passed"))
        assertEquals(setOf(sleeper.id), (first.machine.state.value as SchedulerState.Ready).delivering)

        val restoredMachine = SpecMachine(SchedulerState.Ready(listOf(sleeper)))
        val restored = ActionsFixture(this, restoredMachine, journal = MemoryJournal(listOf(completed)))
        restored.actions.start()
        runCurrent()
        val event = restoredMachine.sent.filterIsInstance<SchedulerIntent.Internal.Observed>().single().event
        assertEquals(completed.payload, event.payload)
        assertEquals(listOf(completed), restored.journal.records)
        restoredMachine.send(SchedulerIntent.Internal.Delivered(sleeper.id, WakeReason.Event(event)))
        runCurrent()
        assertTrue((restoredMachine.state.value as SchedulerState.Ready).wakes.isEmpty())
        assertEquals(listOf(sleeper), restored.wakeStorage.wakes)
        assertEquals(listOf(completed), restored.journal.records)
        // The journal is retired only after this same revision can no longer return from disk.
        restored.persistWakes()
        runCurrent()
        assertTrue(restored.journal.records.isEmpty())
    }

    @Test
    fun `cancelled recovery before loading does not consume the journal`() = runTest {
        val record = ActionRecord(action, "command", START)
        val fixture = ActionsFixture(this, SpecMachine(SchedulerState.Loading), journal = MemoryJournal(listOf(record)))
        val recovery = async { fixture.actions.recover() }
        runCurrent()
        recovery.cancelAndJoin()
        assertEquals(listOf(record), fixture.journal.records)
    }

    @Test
    fun `enabling replays completed results but never interrupts a currently running action`() = runTest {
        val sleeper = scheduled("w1", events = setOf(EventKeys.actionFinished(action)))
        val toggles = Toggles(enabled = false)
        val fixture = ActionsFixture(this, SpecMachine(SchedulerState.Ready(listOf(sleeper))), toggles = toggles)
        fixture.actions.startCommand(action, SESSION, PROJECT, "make test", 5.minutes)
        toggles.isEnabled.value = true
        runCurrent()
        assertTrue(fixture.machine.sent.none { it is SchedulerIntent.Internal.Observed })
        assertNull(fixture.journal.records.single().payload)
        toggles.isEnabled.value = false
        runCurrent()
        fixture.commands.result.complete(CommandOutcome(0, "done while disabled"))
        runCurrent()
        assertTrue(fixture.journal.records.single().payload.orEmpty().contains("done while disabled"))
        assertTrue(fixture.machine.sent.none { it is SchedulerIntent.Internal.Observed })
        toggles.isEnabled.value = true
        runCurrent()
        val observed = fixture.machine.sent.filterIsInstance<SchedulerIntent.Internal.Observed>().single()
        assertTrue(observed.event.payload.orEmpty().contains("done while disabled"))
    }

    @Test
    fun `cancelled waits retire a completed action result`() = runTest {
        val completed = ActionRecord(action, "command", START, "status: finished")
        val sleeper = scheduled("w1", events = setOf(EventKeys.actionFinished(action)))
        val toggles = Toggles(enabled = false)
        val fixture = ActionsFixture(
            this,
            SpecMachine(SchedulerState.Ready(listOf(sleeper))),
            journal = MemoryJournal(listOf(completed)),
            toggles = toggles,
        )
        fixture.actions.start()
        fixture.machine.send(SchedulerIntent.Public.Cancel(WakeId("w1"), SESSION))
        fixture.persistWakes()
        toggles.isEnabled.value = true
        runCurrent()
        assertTrue(fixture.journal.records.isEmpty())
        assertTrue(fixture.machine.sent.none { it is SchedulerIntent.Internal.Observed })
    }

    @Test
    fun `cancelling the caller after helper handoff keeps the accepted helper supervised`() = runTest {
        val mayAccept = CompletableDeferred<Unit>()
        val host = HelperHostFake().apply { beforePrompt = { mayAccept.await() } }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        val caller = async { fixture.actions.startAgent(action, spawn) }
        runCurrent()
        caller.cancelAndJoin()
        assertEquals(listOf(action), fixture.journal.records.map { it.id })
        mayAccept.complete(Unit)
        runCurrent()
        assertTrue(fixture.actions.isHelper(OTHER))
        host.results[spawn.prompt.request] = HelperResult(spawn.prompt.request, HelperOutcome.Completed, "done")
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertTrue(events.any { it.key == EventKeys.actionFinished(action) })
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `closing the profile during helper startup preserves its interruption record`() = runTest {
        val lifetime = Job(backgroundScope.coroutineContext[Job])
        val profile = TestScopeHandle(CoroutineScope(backgroundScope.coroutineContext + lifetime))
        val host = HelperHostFake().apply { beforeCreate = { awaitCancellation() } }
        val sleeper = scheduled("w1", events = setOf(EventKeys.actionFinished(action)))
        val fixture = ActionsFixture(
            this,
            SpecMachine(SchedulerState.Ready(listOf(sleeper))),
            hosts = setOf(host),
            profile = profile,
        )
        val caller = launch { fixture.actions.startAgent(action, spawn) }
        runCurrent()
        profile.isClosed = true
        profile.coroutineScope.cancel()
        runCurrent()
        caller.join()
        assertEquals(listOf(action), fixture.journal.records.map { it.id })
        val restored = ActionsFixture(
            this,
            SpecMachine(SchedulerState.Ready(listOf(sleeper))),
            journal = fixture.journal,
        )
        restored.actions.recover()
        runCurrent()
        val observed = restored.machine.sent.filterIsInstance<SchedulerIntent.Internal.Observed>().single()
        assertTrue(observed.event.payload.orEmpty().startsWith("status: interrupted"))
    }
}
