package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityKind
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledHelperActionsTest {
    private val action = ActionId("scheduled")
    private val request = RequestId("attempt")
    private val spawn = SpawnRequest(SESSION, PROJECT, TARGET, "Helper", WakePrompt(request, "Task", "Directive"))
    private val helper = HelperId("durable-helper")

    @Test
    fun `timeout retains its single scheduled slot until exact cancellation is confirmed`() = runTest {
        val host = HelperHostFake().apply { cancellation = { HelperCancellation.Unconfirmed(it) } }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        assertNull(fixture.actions.startAgent(action, spawn))
        runCurrent()
        assertEquals(BackgroundCapacityKind.Scheduled, fixture.reservations().single().kind)
        assertEquals(request, fixture.journal.records.single().request)
        assertEquals(host.metadata.keys.single(), fixture.journal.records.single().helper)
        advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(listOf(request), host.cancellations)
        assertEquals(1, fixture.reservations().size)
        assertNull(fixture.journal.records.single().payload)
        assertTrue(events.isEmpty())
        host.cancellation = { HelperCancellation.Terminal(HelperResult(it, HelperOutcome.Cancelled, "")) }
        fixture.actions.recover()
        runCurrent()
        assertEquals(listOf(request, request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
        assertTrue(events.single().payload.orEmpty().contains("timed out"))
        fixture.actions.recover()
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test
    fun `session events and results for another attempt cannot complete a scheduled helper`() = runTest {
        val host = HelperHostFake()
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        assertNull(fixture.actions.startAgent(action, spawn))
        host.results[RequestId("other")] = HelperResult(RequestId("other"), HelperOutcome.Completed, "unrelated")
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertNull(fixture.journal.records.single().payload)
        assertEquals(1, fixture.reservations().size)
        host.results[request] = HelperResult(request, HelperOutcome.Completed, "answer")
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
        assertTrue(host.cancellations.isEmpty())
    }

    @Test
    fun `restart reacquires a scheduled slot and retries only the durable helper attempt`() = runTest {
        val record = ActionRecord(action, "agent", START, helper = helper, parent = SESSION, request = request)
        val host = HelperHostFake().apply {
            metadata[helper] = HelperMetadata(helper, action, SESSION, OTHER, request)
            cancellation = { HelperCancellation.Unconfirmed(it) }
        }
        val fixture = ActionsFixture(this, SpecMachine(), journal = MemoryJournal(listOf(record)), hosts = setOf(host))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        fixture.actions.recover()
        runCurrent()
        assertEquals(BackgroundCapacityKind.Scheduled, fixture.reservations().single().kind)
        assertEquals(listOf(request), host.cancellations)
        assertTrue(host.prompts.isEmpty())
        assertEquals(listOf(record), fixture.journal.records)
        assertTrue(events.isEmpty())
        host.results[request] = HelperResult(request, HelperOutcome.Completed, "finished before restart")
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertEquals(listOf(request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
        assertTrue(events.single { it.key == EventKeys.actionFinished(action) }.payload.orEmpty().contains("confirmed"))
    }

    @Test
    fun `recovery retains a mismatching durable request without cancelling someone else's attempt`() = runTest {
        val record = ActionRecord(action, "agent", START, helper = helper, parent = SESSION, request = request)
        val host = HelperHostFake().apply {
            metadata[helper] = HelperMetadata(helper, action, SESSION, OTHER, RequestId("different"))
        }
        val fixture = ActionsFixture(this, SpecMachine(), journal = MemoryJournal(listOf(record)), hosts = setOf(host))
        fixture.actions.recover()
        runCurrent()
        assertEquals(listOf(record), fixture.journal.records)
        assertEquals(1, fixture.reservations().size)
        assertTrue(host.cancellations.isEmpty())
        host.metadata[helper] = checkNotNull(host.metadata[helper]).copy(lastRequest = request)
        fixture.actions.recover()
        runCurrent()
        assertEquals(listOf(request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
    }

    @Test
    fun `ownerless old records retain a profile slot and never synthesize completion proof`() = runTest {
        val record = ActionRecord(action, "agent", START)
        val fixture = ActionsFixture(this, SpecMachine(), journal = MemoryJournal(listOf(record)))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        fixture.actions.start()
        runCurrent()
        fixture.actions.recover()
        runCurrent()
        assertEquals(listOf(record), fixture.journal.records)
        assertEquals(1, fixture.reservations().size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a cancelled host cleanup wait retains supervision and can be retried`() = runTest {
        val host = HelperHostFake().apply { cancellation = { throw CancellationException("Host wait cancelled") } }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        assertNull(fixture.actions.startAgent(action, spawn))
        advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(listOf(request), host.cancellations)
        assertEquals(1, fixture.reservations().size)
        assertNull(fixture.journal.records.single().payload)
        host.cancellation = { HelperCancellation.Terminal(HelperResult(it, HelperOutcome.Cancelled, "")) }
        fixture.actions.recover()
        runCurrent()
        assertEquals(listOf(request, request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `timeout of pending submission does not release capacity before a barrier`() = runTest {
        val host = HelperHostFake().apply {
            beforePrompt = { awaitCancellation() }
            cancellation = { HelperCancellation.Unconfirmed(it) }
        }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        val startup = async { fixture.actions.startAgent(action, spawn) }
        runCurrent()
        advanceTimeBy(6.hours)
        runCurrent()
        assertNull(startup.await())
        assertEquals(listOf(request), host.cancellations)
        assertNull(fixture.journal.records.single().payload)
        assertEquals(1, fixture.reservations().size)
        host.cancellation = { HelperCancellation.NotSubmitted(it) }
        fixture.actions.recover()
        runCurrent()
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `disabled scheduling still reconciles helper termination without delivering wakes`() = runTest {
        val record = ActionRecord(action, "agent", START, helper = helper, parent = SESSION, request = request)
        val host = HelperHostFake().apply {
            metadata[helper] = HelperMetadata(helper, action, SESSION, OTHER, request)
        }
        val fixture = ActionsFixture(
            this,
            SpecMachine(),
            journal = MemoryJournal(listOf(record)),
            hosts = setOf(host),
            toggles = Toggles(enabled = false),
        )
        fixture.actions.start()
        runCurrent()
        assertEquals(listOf(request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.single().payload.orEmpty().contains("confirmed"))
        assertTrue(fixture.machine.sent.isEmpty())
    }

    @Test
    fun `host cancelling its result observer cannot abandon the lease before cleanup`() = runTest {
        val host = HelperHostFake().apply {
            beforeResult = { throw CancellationException("Result observer cancelled") }
        }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        assertNull(fixture.actions.startAgent(action, spawn))
        runCurrent()
        assertEquals(1, fixture.reservations().size)
        assertNull(fixture.journal.records.single().payload)
        host.beforeResult = {}
        fixture.actions.recover()
        runCurrent()
        assertEquals(listOf(request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `unconfirmed cleanup is retried when native work ends without another event`() = runTest {
        val record = ActionRecord(action, "agent", START, helper = helper, parent = SESSION, request = request)
        val host = HelperHostFake().apply {
            metadata[helper] = HelperMetadata(helper, action, SESSION, OTHER, request)
            cancellation = { HelperCancellation.Unconfirmed(it) }
        }
        val fixture = ActionsFixture(this, SpecMachine(), journal = MemoryJournal(listOf(record)), hosts = setOf(host))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        fixture.actions.start()
        runCurrent()
        assertEquals(listOf(request), host.cancellations)
        assertEquals(1, fixture.reservations().size)
        assertNull(fixture.journal.records.single().payload)
        assertTrue(events.isEmpty())
        host.cancellation = { HelperCancellation.Terminal(HelperResult(it, HelperOutcome.Cancelled, "")) }
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(listOf(request, request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
        assertEquals(EventKeys.actionFinished(action), events.single().key)
    }

    @Test
    fun `exact terminal result is observed even when no session event arrives`() = runTest {
        val host = HelperHostFake()
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        assertNull(fixture.actions.startAgent(action, spawn))
        runCurrent()
        assertEquals(1, fixture.reservations().size)
        host.results[request] = HelperResult(request, HelperOutcome.Completed, "answer")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
        assertTrue(host.cancellations.isEmpty())
    }

    @Test
    fun `host cancelling metadata lookup cannot abandon the recovery supervisor`() = runTest {
        val record = ActionRecord(action, "agent", START, helper = helper, parent = SESSION, request = request)
        var lookups = 0
        val host = HelperHostFake().apply {
            metadata[helper] = HelperMetadata(helper, action, SESSION, OTHER, request)
            beforeMetadata = {
                lookups++
                if (lookups == 1) throw CancellationException("Host metadata wait cancelled")
            }
        }
        val fixture = ActionsFixture(this, SpecMachine(), journal = MemoryJournal(listOf(record)), hosts = setOf(host))
        fixture.actions.start()
        runCurrent()
        assertEquals(1, lookups)
        assertEquals(1, fixture.reservations().size)
        assertNull(fixture.journal.records.single().payload)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, lookups)
        assertEquals(listOf(request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
    }
}

private fun ActionsFixture.reservations() = (capacityMachine.state.value as BackgroundCapacityState.Ready).active
