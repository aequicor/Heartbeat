package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Instance-owned timer registrations. Staging never invokes author code. Every timer waits for the exact runtime
 * publication and enters through [HarnessTimerInvoker]; the next delay begins only after actual termination.
 * Pumps use the injected control dispatcher and neutral ancestry, while each callback keeps its registration
 * origin. Disposal wakes pending delays without cancelling an already admitted callback or its runtime budget.
 */
internal class HarnessTimers(
    private val target: HarnessInstanceTarget,
    private val slots: HarnessTimerSlots,
    private val invoker: HarnessTimerInvoker,
    private val origins: HarnessCallOrigins,
    controlDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val owner = slots.stage(target.request, target.access)
    private val state = MutableStateFlow(TimerRegistrations())
    private val log = Log.tag("HarnessTimers")
    private val pumps = CoroutineScope(
        target.access.scope.coroutineContext + controlDispatcher + origins.context(HarnessCallOrigin()),
    )

    fun every(interval: Duration, handler: suspend () -> Unit): ScriptRegistration {
        require(
            interval.isFinite() && interval >= HarnessLimits.MIN_TIMER,
        ) { "Timer interval is too short or infinite" }
        return register(TimerSchedule.Repeating(interval), handler)
    }

    fun at(instant: Instant, handler: suspend () -> Unit): ScriptRegistration =
        register(TimerSchedule.Deadline(instant), handler)

    /** Called in the instance's closed Committing phase. */
    fun tryCommitPublication(): Boolean = slots.publish(owner)

    @HighFrequency
    private fun register(schedule: TimerSchedule, handler: suspend () -> Unit): ScriptRegistration {
        log.v { "register timer" }
        val id = checkNotNull(slots.reserve(owner)) { "Timer registration unavailable or quota exceeded" }
        val callback = HarnessCallback(id, origins.current(), handler)
        val registration = HarnessTimerRegistration(callback) {
            slots.release(owner, id)
            state.update { it.copy(registrations = it.registrations - id) }
        }
        var isRegistered = false
        try {
            while (!isRegistered) {
                val before = state.value
                check(!before.isClosed) { "Timer owner is closed" }
                val after = before.copy(registrations = before.registrations + (id to registration))
                isRegistered = state.compareAndSet(before, after)
            }
        } finally {
            if (!isRegistered) registration.dispose()
        }
        pumps.launch { run(schedule, registration) }.invokeOnCompletion { registration.dispose() }
        return registration
    }

    private suspend fun run(schedule: TimerSchedule, registration: HarnessTimerRegistration) {
        if (!awaitPublication(registration)) return
        do {
            val delay = when (schedule) {
                is TimerSchedule.Repeating -> schedule.interval
                is TimerSchedule.Deadline -> schedule.at - clock.now()
            }
            if (!registration.awaitDelay(delay) || !invoker.invoke(target, registration.callback)) break
        } while (schedule is TimerSchedule.Repeating && registration.callback.isActive)
    }

    private suspend fun awaitPublication(registration: HarnessTimerRegistration): Boolean = coroutineScope {
        val publication = async { invoker.awaitPublication(target) }
        try {
            select {
                registration.disposed.onAwait { false }
                publication.onAwait { it && registration.callback.isActive }
            }
        } finally {
            publication.cancel()
        }
    }

    @HighFrequency
    fun close() {
        log.v { "close timer registrations" }
        while (true) {
            val before = state.value
            if (before.isClosed) return
            if (state.compareAndSet(before, TimerRegistrations(isClosed = true))) {
                before.registrations.values.forEach { it.dispose() }
                slots.close(owner)
                return
            }
        }
    }
}

private data class TimerRegistrations(
    val isClosed: Boolean = false,
    val registrations: Map<Long, HarnessTimerRegistration> = emptyMap(),
)

private sealed interface TimerSchedule {
    data class Repeating(val interval: Duration) : TimerSchedule
    data class Deadline(val at: Instant) : TimerSchedule
}
