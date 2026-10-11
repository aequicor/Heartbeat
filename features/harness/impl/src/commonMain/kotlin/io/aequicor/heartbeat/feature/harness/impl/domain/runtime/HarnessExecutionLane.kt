package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Shared execution capacity counts executing dispatcher tasks, including code blocked in ordinary JVM calls.
 * Suspension releases execution capacity; an activation never holds a lifetime permit. Per-instance views
 * serialize execution while retaining the shared four-worker ceiling. [isExhausted] is an admission snapshot,
 * not a reservation: queued work remains constrained by the dispatcher if concurrent admission races.
 * [timeSource] measures how long the work holding an instance's worker has run against its own budget.
 */
internal class HarnessExecutionLane(
    io: CoroutineDispatcher,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val log = Log.tag("HarnessLane")
    private val occupied = MutableStateFlow(0)
    private val starvation = MutableStateFlow(false)
    private val shared = TrackedDispatcher(io.limitedParallelism(EXECUTION_PARALLELISM), occupied, starvation)

    val isExhausted: Boolean get() = occupied.value >= EXECUTION_PARALLELISM

    /**
     * Waits at most [slice] for a free worker, like [isExhausted] reserving nothing, and returns the total wait
     * after [waited] (unchanged once a worker is free). Waiting is expected; a lane that stays exhausted past
     * [reportAfter] in total is reported once per exhausted period.
     */
    suspend fun awaitCapacity(slice: Duration, waited: Duration, reportAfter: Duration): Duration {
        if (withTimeoutOrNull(slice) { occupied.first { it < EXECUTION_PARALLELISM } } != null) return waited
        val total = waited + slice
        if (waited < reportAfter && total >= reportAfter) {
            if (markStarved()) {
                log.w { "Harness execution lane stays exhausted; activation waits for a free worker" }
            } else {
                log.v { "harness execution lane still exhausted; activation waits" }
            }
        }
        return total
    }

    /**
     * True once per exhausted period: the first caller that observes starvation reports it, later ones stay quiet
     * until a worker frees up again.
     */
    fun markStarved(): Boolean = starvation.compareAndSet(expect = false, update = true).also { isFirst ->
        if (isFirst) log.v { "execution lane starvation: false -> true" }
    }

    /** A serialized view of the shared lane that also knows whether its own work currently holds the worker. */
    fun instanceDispatcher(): HarnessLaneDispatcher =
        HarnessLaneDispatcher(shared.limitedParallelism(1), this, timeSource)
}

/** What currently holds an instance's serialized worker, as seen by a callback that could not start. */
internal enum class HarnessLaneOccupancy {
    /** Nothing of this instance executes: only foreign work can be keeping the shared lane exhausted. */
    Idle,

    /** The instance's own call or background work runs within its budget: queued calls wait as expected. */
    WithinBudget,

    /** The instance's own work outlived its budget: calls queued behind it fail with this instance. */
    Overdue,
}

/**
 * Budget of one invocation, carried in its task context. The lane stamps the first actual start, and the waiter
 * marks it expired once it stopped waiting, so code that keeps running afterwards is visibly overdue.
 */
internal class HarnessCallBudget(val budget: Duration) : AbstractCoroutineContextElement(Key) {
    private val startedAt = MutableStateFlow<TimeMark?>(null)
    private val expired = MutableStateFlow(false)

    @HighFrequency
    fun markStarted(timeSource: TimeSource) {
        if (startedAt.value == null && startedAt.compareAndSet(null, timeSource.markNow())) {
            log.v { "startedAt: null -> started" }
        }
    }

    @HighFrequency
    fun expire() {
        if (expired.compareAndSet(expect = false, update = true)) log.v { "expired: false -> true" }
    }

    val isOverdue: Boolean get() = expired.value || startedAt.value?.let { it.elapsedNow() >= budget } == true

    companion object Key : CoroutineContext.Key<HarnessCallBudget> {
        private val log = Log.tag("HarnessLane")
    }
}

/**
 * One instance's serialized view. It records which task actually executes, including code blocked in an ordinary
 * JVM call, so a queued callback that cannot start knows whether its own instance holds the worker and whether
 * that work is still within its budget. Background work without a call budget gets [HarnessInvocationBudget.Background]
 * per continuous execution slice.
 */
internal class HarnessLaneDispatcher(
    private val delegate: CoroutineDispatcher,
    private val lane: HarnessExecutionLane,
    private val timeSource: TimeSource,
) : CoroutineDispatcher() {
    private val occupant = MutableStateFlow<HarnessCallBudget?>(null)
    private val log = Log.tag("HarnessLane")

    val occupancy: HarnessLaneOccupancy
        get() = when (occupant.value?.isOverdue) {
            null -> HarnessLaneOccupancy.Idle
            true -> HarnessLaneOccupancy.Overdue
            false -> HarnessLaneOccupancy.WithinBudget
        }

    fun markStarved(): Boolean = lane.markStarved()

    @HighFrequency
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val call = context[HarnessCallBudget]
        delegate.dispatch(context) {
            log.v { "instance lane task starts" }
            val current = call ?: HarnessCallBudget(HarnessInvocationBudget.Background)
            current.markStarted(timeSource)
            occupant.value = current
            try {
                block.run()
            } finally {
                occupant.value = null
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
