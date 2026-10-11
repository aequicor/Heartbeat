package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityKind
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledHelperActionsTest {
    private val action = ActionId("scheduled")
    private val request = RequestId("attempt")
    private val spawn = SpawnRequest(SESSION, PROJECT, TARGET, "Helper", WakePrompt(request, "Task", "Directive"))
    private val helper = HelperId("durable-helper")

    @Test
    fun `scheduled helper receives exact immutable initiating request before its first prompt`() = runTest {
        val host = HelperHostFake()
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        val initiator = RequestInitiator(SESSION, RequestId("source-R"))
        assertNull(fixture.actions.startAgent(action, spawn, initiator))
        assertEquals(HelperHandoff(initiator = initiator), host.prompts.single().handoff)
        assertEquals(listOf("Directive"), host.prompts.single().directives)
        assertEquals(initiator, fixture.journal.records.single().initiator)
        host.results[request] = HelperResult(request, HelperOutcome.Completed, "done")
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertTrue(fixture.reservations().isEmpty())
    }

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
        // Unconfirmed cleanup backs off: the retry follows its delay, not a session event.
        advanceTimeBy(5.seconds)
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
    fun `legacy records without helper identity are reported interrupted and hold no slot`() = runTest {
        val legacy = ActionRecord(action, "agent", START)
        val parentless = ActionRecord(ActionId("parentless"), "agent", START, helper = helper, request = request)
        val fixture = ActionsFixture(
            this,
            SpecMachine(),
            journal = MemoryJournal(listOf(legacy, parentless)),
            toggles = Toggles(enabled = false),
        )
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        fixture.actions.start()
        runCurrent()
        assertTrue(fixture.reservations().isEmpty())
        assertEquals(
            listOf(EventKeys.actionFinished(action), EventKeys.actionFinished(parentless.id)),
            events.map { it.key },
        )
        assertTrue(events.all { it.payload.orEmpty().contains("interrupted") })
        assertTrue(fixture.journal.records.all { it.payload.orEmpty().contains("interrupted") })
        fixture.actions.recover()
        runCurrent()
        assertEquals(2, events.size)
        assertTrue(fixture.reservations().isEmpty())
    }

    @Test
    fun `restart delivers a result confirmed before restart instead of an interruption`() = runTest {
        val record = ActionRecord(action, "agent", START, helper = helper, parent = SESSION, request = request)
        val host = HelperHostFake().apply {
            metadata[helper] = HelperMetadata(helper, action, SESSION, OTHER, request)
            results[request] = HelperResult(request, HelperOutcome.Completed, "finished before restart")
        }
        val fixture = ActionsFixture(this, SpecMachine(), journal = MemoryJournal(listOf(record)), hosts = setOf(host))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        fixture.actions.start()
        runCurrent()
        assertTrue(host.cancellations.isEmpty())
        assertTrue(fixture.reservations().isEmpty())
        val payload = events.single { it.key == EventKeys.actionFinished(action) }.payload.orEmpty()
        assertTrue(payload.contains("finished before restart"))
        assertFalse(payload.contains("interrupted"))
    }

    @Test
    fun `an uncertain prompt failure keeps a possibly accepted turn running and finishes with its result`() = runTest {
        val host = HelperHostFake().apply {
            beforePrompt = { throw IllegalStateException("Lost acceptance") }
            // The host's receipt is Submitting or Accepted: revocation cannot prove that nothing was sent.
            revocation = { HelperCancellation.Unconfirmed(it) }
        }
        // The profile supervises its jobs: a failed host call stays in its own operation.
        val profile = TestScopeHandle(
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
        )
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host), profile = profile)
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        assertNull(fixture.actions.startAgent(action, spawn))
        runCurrent()
        assertEquals(1, host.prompts.size)
        assertEquals(1, fixture.reservations().size)
        assertTrue(events.isEmpty())
        host.results[request] = HelperResult(request, HelperOutcome.Completed, "accepted after all")
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(events.single { it.key == EventKeys.actionFinished(action) }.payload.orEmpty().contains("after all"))
        // Only the non-destructive barrier after the failed prompt: the accepted turn was never stopped.
        assertEquals(listOf(request), host.revocations)
        assertTrue(host.cancellations.isEmpty())
    }

    @Test
    fun `a possibly accepted turn after a failed prompt is stopped only by its timeout`() = runTest {
        val host = HelperHostFake().apply {
            beforePrompt = { throw IllegalStateException("Lost acceptance") }
            cancellation = { HelperCancellation.Terminal(HelperResult(it, HelperOutcome.Cancelled, "")) }
        }
        val profile = TestScopeHandle(
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
        )
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host), profile = profile)
        assertNull(fixture.actions.startAgent(action, spawn))
        runCurrent()
        advanceTimeBy(5.hours)
        runCurrent()
        assertEquals(listOf(request), host.revocations)
        assertTrue(host.cancellations.isEmpty())
        assertEquals(1, fixture.reservations().size)
        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(listOf(request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
    }

    @Test
    fun `a failed prompt proven not submitted frees its slot at once instead of awaiting the timeout`() = runTest {
        val host = HelperHostFake().apply {
            beforePrompt = { throw IllegalStateException("Prompt refused before its journal") }
            revocation = { HelperCancellation.NotSubmitted(it) }
        }
        val profile = TestScopeHandle(
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
        )
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host), profile = profile)
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        assertNull(fixture.actions.startAgent(action, spawn))
        runCurrent()
        assertEquals(listOf(request), host.revocations)
        assertTrue(host.cancellations.isEmpty())
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
        val payload = events.single { it.key == EventKeys.actionFinished(action) }.payload.orEmpty()
        assertTrue(payload.contains("not submitted"))
    }

    @Test
    fun `unconfirmed cleanup backs off and ignores session events and other reservations`() = runTest {
        val host = HelperHostFake().apply { cancellation = { HelperCancellation.Unconfirmed(it) } }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        assertNull(fixture.actions.startAgent(action, spawn))
        advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(1, host.cancellations.size)
        repeat(3) {
            fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
            runCurrent()
        }
        // Another reservation runs a recovery pass; it must not cut the cleanup backoff short.
        val second = spawn.copy(prompt = WakePrompt(RequestId("second"), "Task", "Directive"))
        assertNull(fixture.actions.startAgent(ActionId("second"), second))
        runCurrent()
        assertEquals(1, host.cancellations.count { it == request })
        // Retries follow 5 s, 10 s and 20 s delays instead of one every 5 s or per event.
        advanceTimeBy(36.seconds)
        runCurrent()
        assertEquals(4, host.cancellations.count { it == request })
        assertEquals(1, fixture.reservations().count { it.id == action })
    }

    @Test
    fun `failing recovery backs off and ignores session events until the delay passes`() = runTest {
        val record = ActionRecord(action, "agent", START, helper = helper, parent = SESSION, request = request)
        var lookups = 0
        val host = HelperHostFake().apply {
            metadata[helper] = HelperMetadata(helper, action, SESSION, OTHER, request)
            beforeMetadata = {
                lookups++
                throw IllegalStateException("Host metadata unavailable")
            }
        }
        val fixture = ActionsFixture(this, SpecMachine(), journal = MemoryJournal(listOf(record)), hosts = setOf(host))
        fixture.actions.start()
        runCurrent()
        assertEquals(1, lookups)
        repeat(3) {
            fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
            runCurrent()
        }
        assertEquals(1, lookups)
        // Attempts follow 5 s, 10 s and 20 s delays instead of one every 5 s.
        advanceTimeBy(40.seconds)
        runCurrent()
        assertEquals(4, lookups)
        host.beforeMetadata = { lookups++ }
        advanceTimeBy(40.seconds)
        runCurrent()
        assertEquals(5, lookups)
        assertEquals(listOf(request), host.cancellations)
        assertTrue(fixture.reservations().isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
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
