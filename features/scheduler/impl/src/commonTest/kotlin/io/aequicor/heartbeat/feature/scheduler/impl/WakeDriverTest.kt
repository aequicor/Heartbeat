package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.spi.SchedulerEventSource
import io.aequicor.heartbeat.feature.scheduler.api.spi.SourceEvent
import io.aequicor.heartbeat.feature.scheduler.impl.data.InMemorySchedulerBus
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeDriver
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class WakeDriverTest {
    private val done = EventKeys.custom("build.done")

    private fun TestScope.driver(
        machine: SpecMachine,
        toggles: Toggles = Toggles(),
        sources: Set<SchedulerEventSource> = emptySet(),
    ): InMemorySchedulerBus {
        val clock = VirtualClock(testScheduler)
        val bus = InMemorySchedulerBus(clock)
        WakeDriver(machine, bus, toggles.isEnabled, clock, sources).start(backgroundScope)
        runCurrent()
        return bus
    }

    @Test
    fun `only awaited events reach the machine`() = runTest {
        val machine = SpecMachine(SchedulerState.Ready(listOf(scheduled("w1", events = setOf(done)))))
        val bus = driver(machine)
        bus.publish(EventKeys.NetworkLost, EventOrigin.System)
        bus.publish(done, EventOrigin.Host, "ok")
        runCurrent()
        val observed = machine.sent.filterIsInstance<SchedulerIntent.Internal.Observed>()
        assertEquals(listOf(done), observed.map { it.event.key })
        assertEquals(setOf(WakeId("w1")), (machine.state.value as SchedulerState.Ready).delivering)
    }

    @Test
    fun `the timer ticks at the earliest deadline, also beyond the sleep cap`() = runTest {
        val machine = SpecMachine(
            SchedulerState.Ready(
                listOf(scheduled("late", deadline = START + 2.hours), scheduled("soon", deadline = START + 5.minutes)),
            ),
        )
        driver(machine)
        advanceTimeBy(5.minutes - 1.minutes)
        assertTrue(machine.sent.none { it is SchedulerIntent.Internal.Tick })
        advanceTimeBy(1.minutes + 1.minutes)
        assertEquals(1, machine.sent.count { it is SchedulerIntent.Internal.Tick })
        advanceTimeBy(2.hours)
        val ready = machine.state.value as SchedulerState.Ready
        assertEquals(setOf(WakeId("soon"), WakeId("late")), ready.delivering)
    }

    @Test
    fun `nothing is fed while the toggle is off`() = runTest {
        val toggles = Toggles(enabled = false)
        val machine = SpecMachine(SchedulerState.Ready(listOf(scheduled("w1", deadline = START))))
        driver(machine, toggles)
        advanceTimeBy(1.hours)
        assertTrue(machine.sent.isEmpty())
        toggles.isEnabled.value = true
        runCurrent()
        assertEquals(1, machine.sent.count { it is SchedulerIntent.Internal.Tick })
    }

    @Test
    fun `platform sources publish on the bus`() = runTest {
        val machine = SpecMachine(
            SchedulerState.Ready(listOf(scheduled("w1", events = setOf(EventKeys.NetworkAvailable)))),
        )
        driver(machine, sources = setOf(SchedulerEventSource { flowOf(SourceEvent(EventKeys.NetworkAvailable)) }))
        runCurrent()
        val observed = machine.sent.filterIsInstance<SchedulerIntent.Internal.Observed>().single()
        assertEquals(EventOrigin.System, observed.event.origin)
    }
}
