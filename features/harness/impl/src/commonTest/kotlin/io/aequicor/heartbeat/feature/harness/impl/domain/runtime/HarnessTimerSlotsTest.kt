package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessTimerSlotsTest {
    @Test
    fun `concurrent dynamic registration across items cannot exceed shared quota`() = runTest {
        val slots = HarnessTimerSlots()
        val owners = List(4) { owner(slots, item = "item$it") }
        owners.forEach { assertTrue(slots.publish(it)) }
        val start = CompletableDeferred<Unit>()
        val attempts = List(64) { index ->
            async(Dispatchers.Default) {
                start.await()
                slots.reserve(owners[index % owners.size])
            }
        }
        start.complete(Unit)
        val ids = attempts.awaitAll().filterNotNull()
        assertEquals(HarnessLimits.TIMERS, ids.size)
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `private candidate slots do not evict old timers and replacement can reuse all eight`() = runTest {
        val slots = HarnessTimerSlots()
        val old = owner(slots)
        val oldIds = List(HarnessLimits.TIMERS) { assertNotNull(slots.reserve(old)) }
        assertTrue(slots.publish(old))
        val replacement = owner(slots)
        repeat(HarnessLimits.TIMERS) { assertNotNull(slots.reserve(replacement)) }
        assertNull(slots.reserve(replacement))
        assertNull(slots.reserve(old))
        assertTrue(slots.publish(replacement))
        assertNull(slots.reserve(old))
        assertFalse(slots.publish(old))
        oldIds.forEach { slots.release(old, it) }
        slots.close(old)
        assertNull(slots.reserve(replacement))
    }

    @Test
    fun `publication checks other items atomically and a rejected candidate leaves old quota intact`() = runTest {
        val slots = HarnessTimerSlots()
        val old = owner(slots)
        assertTrue(slots.publish(old))
        val other = owner(slots, item = "other")
        val otherIds = List(HarnessLimits.TIMERS - 1) { assertNotNull(slots.reserve(other)) }
        assertTrue(slots.publish(other))
        val replacement = owner(slots)
        repeat(2) { assertNotNull(slots.reserve(replacement)) }
        assertFalse(slots.publish(replacement))
        val last = assertNotNull(slots.reserve(old))
        slots.release(other, otherIds.first())
        assertTrue(slots.publish(replacement))
        slots.release(old, last)
        assertNull(slots.reserve(other))
    }

    @Test
    fun `dynamic items share one quota while harnesses are independent`() = runTest {
        val slots = HarnessTimerSlots()
        val first = owner(slots)
        val second = owner(slots, item = "other")
        val independent = owner(slots, harness = "other")
        listOf(first, second, independent).forEach { assertTrue(slots.publish(it)) }
        repeat(HarnessLimits.TIMERS - 1) { assertNotNull(slots.reserve(first)) }
        val last = assertNotNull(slots.reserve(second))
        assertNull(slots.reserve(first))
        assertNull(slots.reserve(second))
        assertNotNull(slots.reserve(independent))
        slots.release(second, last)
        assertNotNull(slots.reserve(first))
        assertNull(slots.reserve(second))
    }

    @Test
    fun `temporary admission loss cannot release slots and reenable cannot overbook quota`() = runTest {
        val slots = HarnessTimerSlots()
        val retiring = owner(slots)
        repeat(HarnessLimits.TIMERS) { assertNotNull(slots.reserve(retiring)) }
        assertTrue(slots.publish(retiring))
        val access = retiring.access as TimerSlotsAccess
        access.isActive = false
        access.isRegistrationAllowed = false
        assertNull(slots.reserve(retiring))
        val other = owner(slots, item = "other")
        repeat(HarnessLimits.TIMERS) { assertNotNull(slots.reserve(other)) }
        assertFalse(slots.publish(other))
        access.isActive = true
        access.isRegistrationAllowed = true
        assertNull(slots.reserve(retiring))
        assertFalse(slots.publish(other))
        slots.close(retiring)
        slots.close(retiring)
        assertTrue(slots.publish(other))
        assertNull(slots.reserve(other))
    }

    @Test
    fun `closed candidate cannot publish or register and disposal is generation scoped`() = runTest {
        val slots = HarnessTimerSlots()
        val old = owner(slots)
        val id = assertNotNull(slots.reserve(old))
        val replacement = owner(slots)
        val replacementId = assertNotNull(slots.reserve(replacement))
        slots.release(replacement, id)
        assertTrue(slots.publish(replacement))
        slots.close(old)
        assertFalse(slots.publish(old))
        assertNull(slots.reserve(old))
        repeat(HarnessLimits.TIMERS - 1) { assertNotNull(slots.reserve(replacement)) }
        assertNull(slots.reserve(replacement))
        slots.release(replacement, replacementId)
        slots.release(replacement, replacementId)
        assertNotNull(slots.reserve(replacement))
        assertNull(slots.reserve(replacement))
    }

    private fun TestScope.owner(
        slots: HarnessTimerSlots,
        harness: String = "owner",
        item: String = "code",
    ): HarnessTimerOwner {
        val request = runtimeRequest(1)
        val code = (request.item as HarnessItem.Workflow).copy(id = ItemId(item))
        val access = TimerSlotsAccess(backgroundScope, StandardTestDispatcher(testScheduler))
        return slots.stage(request.copy(harness = request.harness.copy(id = HarnessId(harness)), item = code), access)
    }
}

private class TimerSlotsAccess(override val scope: CoroutineScope, override val dispatcher: CoroutineDispatcher) :
    HarnessInstanceAccess {
    override var isActive = true
    override var isRegistrationAllowed = true
    override suspend fun awaitPublication(): Boolean = isActive
}
