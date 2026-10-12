package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityKind
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundReservation
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundActionSlots
import io.aequicor.heartbeat.feature.scheduler.impl.data.GraphCapacityRecords
import io.aequicor.heartbeat.feature.scheduler.impl.data.ProfileBackgroundCapacity
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphSharedCapacityTest {
    @Test
    fun `graphs and helpers share the eighth slot and release wakes waiting helpers`() = runTest {
        val machine = CapacitySpecMachine()
        val capacity = ProfileBackgroundCapacity(machine, TestScopeHandle(backgroundScope), MemoryJournal())
        val slots = BackgroundActionSlots(capacity)
        repeat(7) { capacity.acquireHelper(ActionId("h$it"), ActionId("run${it / 4}"), null) }
        assertNull(slots.reserve(ActionId("graph"), SESSION))
        assertTrue(slots.reserve(ActionId("extra"), OTHER).orEmpty().contains("profile"))
        val waiting = async { capacity.acquireHelper(ActionId("waiting"), ActionId("next"), null) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        slots.release(ActionId("graph"))
        waiting.await()
        assertEquals(8, (machine.state.value as BackgroundCapacityState.Ready).active.size)
    }

    @Test
    fun `native graph reservations restore before helper admission even without graph driver`() = runTest {
        val records = (1..8).map {
            BackgroundReservation(ActionId("g$it"), ActionId("g$it"), SESSION, BackgroundCapacityKind.Scheduled)
        }
        val machine = CapacitySpecMachine()
        val capacity = ProfileBackgroundCapacity(
            machine,
            TestScopeHandle(backgroundScope),
            MemoryJournal(),
            GraphCapacityRecords { records },
        )
        val waiting = async { capacity.acquireHelper(ActionId("helper"), ActionId("run"), null) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(records, (machine.state.value as BackgroundCapacityState.Ready).active)
        capacity.release(records.first().id)
        waiting.await()
    }

    @Test
    fun `graph recovery transfers its reservation without giving its slot to a queued helper`() = runTest {
        val machine = CapacitySpecMachine()
        val capacity = ProfileBackgroundCapacity(machine, TestScopeHandle(backgroundScope), MemoryJournal())
        val slots = BackgroundActionSlots(capacity)
        repeat(7) { capacity.acquireHelper(ActionId("h$it"), ActionId("run${it / 4}"), null) }
        assertNull(slots.reserve(ActionId("old"), SESSION))
        val waiting = async { capacity.acquireHelper(ActionId("helper"), ActionId("next"), null) }
        runCurrent()
        assertTrue(slots.transfer(ActionId("old"), ActionId("new"), SESSION))
        runCurrent()
        assertFalse(waiting.isCompleted)
        val active = (machine.state.value as BackgroundCapacityState.Ready).active
        assertEquals(8, active.size)
        assertTrue(active.any { it.id == ActionId("new") })
        slots.release(ActionId("new"))
        waiting.await()
    }
}
