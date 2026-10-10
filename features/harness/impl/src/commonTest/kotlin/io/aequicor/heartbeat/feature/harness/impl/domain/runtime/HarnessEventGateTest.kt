package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessEventGateTest {
    @Test
    fun `stop rejects queued global events before asynchronous suspension and after immediate reopen`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        fixture.onEvaluate = { script -> script.events.on(SystemEvent::class) { calls++ } }
        val instance = fixture.activate()
        val gate = HarnessEventGate()
        val events = HarnessEventDispatch(fixture.runtime, fixture.sessions) { gate.currentEpoch }
        gate.open()
        events.emitTo(instance, SystemEvent.Started(Instant.fromEpochMilliseconds(1)))
        gate.close()
        assertTrue(instance.isActive)
        runCurrent()
        assertEquals(0, calls)
        gate.open()
        events.emitTo(instance, SystemEvent.Started(Instant.fromEpochMilliseconds(2)))
        gate.close()
        gate.open()
        runCurrent()
        assertEquals(0, calls)
        events.emitTo(instance, SystemEvent.Started(Instant.fromEpochMilliseconds(3)))
        runCurrent()
        assertEquals(1, calls)
    }
}
