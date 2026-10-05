package io.aequicor.heartbeat.feature.scheduler.impl.domain

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.spi.SchedulerEventSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Longest single sleep: deadlines are re-checked at least this often, so wall-clock jumps and OS sleep catch up. */
private val MAX_SLEEP: Duration = 15.minutes

/** Pause before a failed source restarts. */
private val SOURCE_RETRY: Duration = 1.minutes

/**
 * Feeds the scheduler machine while [enabled] (the scheduler toggle) is on: every awaited bus event becomes
 * `Observed`, the earliest pending deadline becomes a `Tick`, and platform sources publish on the bus. Turning the
 * toggle off stops all three; pending wakes stay stored and a past deadline fires on the first tick after it is
 * turned on again.
 */
internal class WakeDriver(
    private val machine: SchedulerMachine,
    private val bus: SchedulerBus,
    private val enabled: Flow<Boolean>,
    private val clock: Clock,
    private val sources: Set<SchedulerEventSource>,
) {
    private val log = Log.tag("WakeDriver")

    /** Runs until [scope] is cancelled. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            enabled.distinctUntilChanged().collectLatest { isEnabled ->
                log.i { "scheduler ${if (isEnabled) "enabled" else "disabled"}" }
                if (isEnabled) drive()
            }
        }
    }

    private suspend fun drive() = coroutineScope {
        // Most events wake nobody; sending them would log an ignored intent at WARN for each one.
        launch { bus.events.filter(::isAwaited).collect { machine.send(SchedulerIntent.Internal.Observed(it)) } }
        launch { runTimer() }
        sources.forEach { source -> launch { runSource(source) } }
    }

    @HighFrequency
    private fun isAwaited(event: BusEvent): Boolean {
        val ready = machine.state.value as? SchedulerState.Ready ?: return false
        return ready.wakes.any { it.id !in ready.delivering && it.matches(event) }
    }

    @OptIn(ExperimentalCoroutinesApi::class) // transformLatest: stable in behaviour, experimental only by annotation
    private suspend fun runTimer() {
        machine.state
            .map { state -> (state as? SchedulerState.Ready)?.nextDeadline() }
            .distinctUntilChanged()
            .transformLatest { deadline ->
                if (deadline == null) return@transformLatest
                while (true) {
                    val wait = deadline - clock.now()
                    if (wait <= Duration.ZERO) break
                    delay(wait.coerceAtMost(MAX_SLEEP))
                }
                emit(Unit)
            }
            .collect { machine.send(SchedulerIntent.Internal.Tick(clock.now())) }
    }

    private suspend fun runSource(source: SchedulerEventSource) {
        val name = source::class.simpleName.orEmpty()
        while (true) {
            try {
                source.events().collect { bus.publish(it.key, EventOrigin.System, it.payload) }
                log.d { "source $name completed" }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "source $name failed, restarting" }
                delay(SOURCE_RETRY)
            }
        }
    }
}

/** The earliest deadline among wakes not being delivered. */
@HighFrequency
internal fun SchedulerState.Ready.nextDeadline(): Instant? =
    wakes.filter { it.id !in delivering }.mapNotNull { it.request.condition.deadline }.minOrNull()
