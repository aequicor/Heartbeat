package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.coroutines.CoroutineContext

/**
 * Shared execution capacity counts executing dispatcher tasks, including code blocked in ordinary JVM calls.
 * Suspension releases execution capacity; an activation never holds a lifetime permit. Per-instance views
 * serialize execution while retaining the shared four-worker ceiling. [isExhausted] is an admission snapshot,
 * not a reservation: queued work remains constrained by the dispatcher if concurrent admission races.
 */
internal class HarnessExecutionLane(io: CoroutineDispatcher) {
    private val log = Log.tag("HarnessLane")
    private val occupied = MutableStateFlow(0)
    private val starvation = MutableStateFlow(false)
    private val shared = TrackedDispatcher(io.limitedParallelism(EXECUTION_PARALLELISM), occupied, starvation)

    val isExhausted: Boolean get() = occupied.value >= EXECUTION_PARALLELISM

    /** Suspends until a worker is free; like [isExhausted] this reserves nothing. */
    suspend fun awaitCapacity() {
        occupied.first { it < EXECUTION_PARALLELISM }
    }

    /**
     * True once per exhausted period: the first caller that observes starvation reports it, later ones stay quiet
     * until a worker frees up again.
     */
    fun markStarved(): Boolean = starvation.compareAndSet(expect = false, update = true).also { isFirst ->
        if (isFirst) log.v { "execution lane starvation: false -> true" }
    }

    /** A serialized view of the shared lane that also knows whether its own work currently holds the worker. */
    fun instanceDispatcher(): HarnessLaneDispatcher = HarnessLaneDispatcher(shared.limitedParallelism(1), this)
}

/**
 * One instance's serialized view. [isBusy] is true while a task of this view is actually executing, including
 * code blocked in an ordinary JVM call: a queued callback that cannot start then waits on its own instance.
 */
internal class HarnessLaneDispatcher(
    private val delegate: CoroutineDispatcher,
    private val lane: HarnessExecutionLane,
) : CoroutineDispatcher() {
    private val running = MutableStateFlow(0)
    private val log = Log.tag("HarnessLane")

    val isBusy: Boolean get() = running.value > 0

    fun markStarved(): Boolean = lane.markStarved()

    @HighFrequency
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            log.v { "instance lane task starts" }
            running.update { it + 1 }
            try {
                block.run()
            } finally {
                running.update { it - 1 }
            }
        }
    }
}

private class TrackedDispatcher(
    private val delegate: CoroutineDispatcher,
    private val occupied: MutableStateFlow<Int>,
    private val starvation: MutableStateFlow<Boolean>,
) : CoroutineDispatcher() {
    @HighFrequency
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            occupied.update { it + 1 }
            try {
                block.run()
            } finally {
                occupied.update { it - 1 }
                if (starvation.value && occupied.value < EXECUTION_PARALLELISM) starvation.value = false
            }
        }
    }
}

private const val EXECUTION_PARALLELISM = 4
