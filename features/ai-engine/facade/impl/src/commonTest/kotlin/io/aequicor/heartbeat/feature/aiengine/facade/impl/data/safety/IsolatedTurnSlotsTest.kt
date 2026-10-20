package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IsolatedTurnSlotsTest {
    @Test
    fun `parallel reviews share the capacity and the next one waits in the queue`() = runTest {
        val slots = IsolatedTurnSlots(capacity = 2, queueLimit = 4, queueWait = 10.seconds)
        assertTrue(slots.acquire())
        assertTrue(slots.acquire())
        val waiting = async { slots.acquire() }
        runCurrent()
        assertEquals(IsolatedTurnLoad(capacity = 2, running = 2, queued = 1), slots.load.value)
        slots.releaseRunning()
        assertTrue(waiting.await())
        assertEquals(IsolatedTurnLoad(capacity = 2, running = 2), slots.load.value)
    }

    @Test
    fun `a finishing review keeps its slot until released and is counted when abandoned`() = runTest {
        val slots = IsolatedTurnSlots(capacity = 1, queueLimit = 4, queueWait = 10.seconds)
        assertTrue(slots.acquire())
        slots.startFinishing()
        assertEquals(IsolatedTurnLoad(capacity = 1, finishing = 1), slots.load.value)
        assertFalse(slots.acquire())
        slots.releaseFinishing(isAbandoned = true)
        assertEquals(IsolatedTurnLoad(capacity = 1, abandoned = 1), slots.load.value)
        assertTrue(slots.acquire())
    }

    @Test
    fun `waiting is bounded in time and the queue is bounded in size`() = runTest {
        val slots = IsolatedTurnSlots(capacity = 1, queueLimit = 1, queueWait = 10.seconds)
        assertTrue(slots.acquire())
        val waiting = async { slots.acquire() }
        runCurrent()
        assertFalse(slots.acquire())
        assertEquals("Выполняется 1 из 1, в очереди 1.", slots.describe())
        advanceTimeBy(11.seconds)
        assertFalse(waiting.await())
        assertEquals(IsolatedTurnLoad(capacity = 1, running = 1), slots.load.value)
    }

    @Test
    fun `a cancelled waiter leaves the queue without taking a slot`() = runTest {
        val slots = IsolatedTurnSlots(capacity = 1, queueLimit = 4, queueWait = 10.seconds)
        assertTrue(slots.acquire())
        val waiting = async { slots.acquire() }
        runCurrent()
        waiting.cancel()
        runCurrent()
        assertEquals(IsolatedTurnLoad(capacity = 1, running = 1), slots.load.value)
        slots.releaseRunning()
        assertTrue(slots.acquire())
    }

    @Test
    fun `a slot handed over as the wait times out is owned by the waiter or stays free`() = runTest {
        val slots = IsolatedTurnSlots(capacity = 1, queueLimit = 4, queueWait = 10.seconds)
        assertTrue(slots.acquire())
        val waiting = async { slots.acquire() }
        launch {
            delay(10.seconds)
            slots.releaseRunning()
        }
        if (waiting.await()) slots.releaseRunning()
        runCurrent()
        assertEquals(IsolatedTurnLoad(capacity = 1), slots.load.value)
        assertTrue(slots.acquire())
    }

    @Test
    fun `a waiter cancelled after its slot was handed over returns it`() = runTest {
        val slots = IsolatedTurnSlots(capacity = 1, queueLimit = 4, queueWait = 10.seconds)
        assertTrue(slots.acquire())
        val waiting = async { slots.acquire() }
        runCurrent()
        slots.releaseRunning()
        waiting.cancel()
        runCurrent()
        assertTrue(waiting.isCancelled)
        assertEquals(IsolatedTurnLoad(capacity = 1), slots.load.value)
        assertTrue(slots.acquire())
    }
}
