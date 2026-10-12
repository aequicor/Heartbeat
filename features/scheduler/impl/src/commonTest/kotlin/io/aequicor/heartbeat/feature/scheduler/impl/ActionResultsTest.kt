package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionResultsTest {
    private val action = ActionId("wf_result")

    @Test
    fun `workflow completion while disabled survives restart and retires only after durable settlement`() = runTest {
        val wake = scheduled("caller", events = setOf(EventKeys.actionFinished(action)))
        val first = ActionsFixture(
            this,
            SpecMachine(SchedulerState.Ready(listOf(wake))),
            toggles = Toggles(enabled = false),
        )
        val writes = mutableListOf<ActionRecord>()
        first.journal.beforeAdd = { writes += it }
        first.results.finish(action, "workflow result")
        assertTrue(writes.all { it.payload == "workflow result" })
        assertEquals(1, writes.size)
        assertTrue(first.machine.sent.none { it is SchedulerIntent.Internal.Observed })
        val restored = ActionsFixture(
            this,
            SpecMachine(SchedulerState.Ready(listOf(wake))),
            journal = MemoryJournal(first.journal.records),
        )
        restored.results.recover()
        val event = restored.machine.sent.filterIsInstance<SchedulerIntent.Internal.Observed>().single().event
        assertEquals("workflow result", event.payload)
        restored.machine.send(SchedulerIntent.Internal.Delivered(wake.id, WakeReason.Event(event)))
        restored.results.recover()
        assertEquals(1, restored.journal.records.size)
        restored.persistWakes()
        restored.results.recover()
        assertTrue(restored.journal.records.isEmpty())
    }

    @Test
    fun `workflow result is persisted before publication and bounded once for replay`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine(), toggles = Toggles(enabled = false))
        val beforeWrite = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        fixture.journal.beforeAdd = {
            beforeWrite.complete(Unit)
            allowWrite.await()
        }
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        val finishing = async { fixture.results.finish(action, "x".repeat(SchedulerLimits.MAX_PAYLOAD + 100)) }
        beforeWrite.await()
        assertTrue(events.isEmpty())
        assertFalse(finishing.isCompleted)
        allowWrite.complete(Unit)
        finishing.await()
        val persisted = fixture.journal.records.single()
        assertEquals(SchedulerLimits.MAX_PAYLOAD, persisted.payload?.length)
        assertEquals(persisted.payload, events.single().payload)
    }

    @Test
    fun `failed or cancelled persistence never publishes a workflow result`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine(), toggles = Toggles(enabled = false))
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.toList(events) }
        fixture.journal.beforeAdd = { error("Storage unavailable") }
        assertFailsWith<IllegalStateException> { fixture.results.finish(action, "not stored") }
        val allowWrite = CompletableDeferred<Unit>()
        fixture.journal.beforeAdd = { allowWrite.await() }
        val finishing = async { fixture.results.finish(action, "cancelled write") }
        runCurrent()
        finishing.cancelAndJoin()
        assertTrue(events.isEmpty())
        assertTrue(fixture.journal.records.isEmpty())
    }
}
