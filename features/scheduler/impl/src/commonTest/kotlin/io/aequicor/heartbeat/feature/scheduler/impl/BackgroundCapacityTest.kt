package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityIntent
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionJournal
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionRecord
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundActionCaller
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundCapacityMachine
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandOutcome
import io.aequicor.heartbeat.feature.scheduler.impl.data.ProfileBackgroundCapacity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class BackgroundCapacityTest {
    @Test
    fun `commands and helpers share capacity and completed commands admit a queued helper`() = runTest {
        val fixture = ActionsFixture(this, SpecMachine())
        repeat(7) {
            fixture.capacity.acquireHelper(ActionId("helper$it"), ActionId("run${it / 4}"), null)
        }
        assertNull(
            fixture.actions.startCommand(
                ActionId("command"),
                BackgroundActionCaller(SESSION),
                PROJECT,
                "work",
                1.minutes,
            ),
        )
        assertTrue(
            fixture.actions.startCommand(ActionId("extra"), BackgroundActionCaller(OTHER), PROJECT, "work", 1.minutes)
                .orEmpty().contains("profile"),
        )
        val waiting = async { fixture.capacity.acquireHelper(ActionId("waiting"), ActionId("new_run"), null) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        fixture.commands.result.complete(CommandOutcome(0, "done"))
        waiting.await()
        val state = fixture.capacityMachine.state.value as BackgroundCapacityState.Ready
        assertEquals(8, state.active.size)
        assertTrue(state.queued.isEmpty())
    }

    @Test
    fun `cancelling a queued helper removes only its reservation`() = runTest {
        val machine = CapacitySpecMachine()
        val capacity = ProfileBackgroundCapacity(machine, TestScopeHandle(backgroundScope), MemoryJournal())
        repeat(4) { capacity.acquireHelper(ActionId("h$it"), ActionId("run"), null) }
        val waiting = async { capacity.acquireHelper(ActionId("waiting"), ActionId("run"), null) }
        runCurrent()
        waiting.cancelAndJoin()
        capacity.release(ActionId("h0"))
        capacity.release(ActionId("h0"))
        val state = machine.state.value as BackgroundCapacityState.Ready
        assertEquals(3, state.active.size)
        assertTrue(state.queued.isEmpty())
    }

    @Test
    fun `cancellation racing a grant returns the newly admitted capacity`() = runTest {
        val machine = CapacitySpecMachine()
        val capacity = ProfileBackgroundCapacity(machine, TestScopeHandle(backgroundScope), MemoryJournal())
        repeat(4) { capacity.acquireHelper(ActionId("h$it"), ActionId("run"), null) }
        val waiting = async { capacity.acquireHelper(ActionId("waiting"), ActionId("run"), null) }
        runCurrent()
        capacity.release(ActionId("h0"))
        waiting.cancelAndJoin()
        val state = machine.state.value as BackgroundCapacityState.Ready
        assertEquals(3, state.active.size)
        assertTrue(state.queued.isEmpty())
    }

    @Test
    fun `cancellation after machine commit before acknowledgement does not leak capacity`() = runTest {
        val state = CapacitySpecMachine()
        val machine = object : BackgroundCapacityMachine by state {
            override suspend fun send(intent: BackgroundCapacityIntent) = state.send(intent).also {
                if (intent is BackgroundCapacityIntent.Public.Acquire) {
                    throw CancellationException("Lost acknowledgement")
                }
            }
        }
        val capacity = ProfileBackgroundCapacity(machine, TestScopeHandle(backgroundScope), MemoryJournal())
        assertFailsWith<CancellationException> {
            capacity.acquireHelper(ActionId("helper"), ActionId("run"), null)
        }
        assertEquals(BackgroundCapacityState.Ready(), state.state.value)
    }

    @Test
    fun `profile cancellation on scope exit cannot abandon a granted reservation`() = runTest {
        val machine = CapacitySpecMachine()
        val profile = object : ScopeHandle by TestScopeHandle(backgroundScope) {
            override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { action() }
        }
        val capacity = ProfileBackgroundCapacity(machine, profile, MemoryJournal())
        assertFailsWith<CancellationException> {
            capacity.acquireHelper(ActionId("helper"), ActionId("run"), null)
        }
        assertEquals(BackgroundCapacityState.Ready(), machine.state.value)
    }

    @Test
    fun `closing the profile removes acquisitions waiting for capacity`() = runTest {
        val machine = CapacitySpecMachine()
        val closing = mutableListOf<() -> Unit>()
        val profile = object : ScopeHandle by TestScopeHandle(backgroundScope) {
            override fun onClose(action: () -> Unit): DisposableHandle {
                closing += action
                return DisposableHandle { closing -= action }
            }
        }
        val capacity = ProfileBackgroundCapacity(machine, profile, MemoryJournal())
        repeat(4) { capacity.acquireHelper(ActionId("h$it"), ActionId("run"), null) }
        val waiting = async { capacity.acquireHelper(ActionId("waiting"), ActionId("run"), null) }
        runCurrent()
        closing.toList().forEach { it() }
        assertFailsWith<CancellationException> { waiting.await() }
        val state = machine.state.value as BackgroundCapacityState.Ready
        assertEquals(4, state.active.size)
        assertTrue(state.queued.isEmpty())
        assertTrue(closing.isEmpty())
    }

    @Test
    fun `a duplicate acquisition cannot release the original reservation`() = runTest {
        val machine = CapacitySpecMachine()
        val capacity = ProfileBackgroundCapacity(machine, TestScopeHandle(backgroundScope), MemoryJournal())
        capacity.acquireHelper(ActionId("helper"), ActionId("run"), null)
        assertFailsWith<IllegalStateException> {
            capacity.acquireHelper(ActionId("helper"), ActionId("other"), null)
        }
        assertEquals(
            listOf(ActionId("helper")),
            (machine.state.value as BackgroundCapacityState.Ready).active.map { it.id },
        )
    }

    @Test
    fun `concurrent first acquisitions await one durable restore and never resurrect released records`() = runTest {
        val persisted = (1..7).map { ActionRecord(ActionId("old$it"), "agent", START) }
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val journal = object : ActionJournal by MemoryJournal(persisted) {
            override suspend fun readAll(): List<ActionRecord> {
                reads++
                gate.await()
                return persisted
            }
        }
        val machine = CapacitySpecMachine()
        val capacity = ProfileBackgroundCapacity(machine, TestScopeHandle(backgroundScope), journal)
        val first = async { capacity.acquireHelper(ActionId("first"), ActionId("run1"), null) }
        val second = async { capacity.acquireHelper(ActionId("second"), ActionId("run2"), null) }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(1, reads)
        assertEquals(BackgroundCapacityState.Ready(), machine.state.value)
        gate.complete(Unit)
        runCurrent()
        assertTrue(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(8, (machine.state.value as BackgroundCapacityState.Ready).active.size)
        capacity.release(persisted.first().id)
        second.await()
        capacity.restore()
        capacity.restore()
        assertEquals(1, reads)
        val active = (machine.state.value as BackgroundCapacityState.Ready).active
        assertEquals(8, active.size)
        assertTrue(active.none { it.id == persisted.first().id })
    }
}
