package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.CoroutineContext

/**
 * Shared execution capacity counts executing dispatcher tasks, including code blocked in ordinary JVM calls.
 * Suspension releases execution capacity; an activation never holds a lifetime permit. Per-instance views
 * serialize execution while retaining the shared four-worker ceiling. [isExhausted] is an admission snapshot,
 * not a reservation: queued work remains constrained by the dispatcher if concurrent admission races.
 */
internal class HarnessExecutionLane(io: CoroutineDispatcher) {
    private val occupied = MutableStateFlow(0)
    private val shared = TrackedDispatcher(io.limitedParallelism(EXECUTION_PARALLELISM), occupied)

    val isExhausted: Boolean get() = occupied.value >= EXECUTION_PARALLELISM

    fun instanceDispatcher(): CoroutineDispatcher = shared.limitedParallelism(1)
}

private class TrackedDispatcher(
    private val delegate: CoroutineDispatcher,
    private val occupied: MutableStateFlow<Int>,
) : CoroutineDispatcher() {
    @HighFrequency
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            occupied.update { it + 1 }
            try {
                block.run()
            } finally {
                occupied.update { it - 1 }
            }
        }
    }
}

private const val EXECUTION_PARALLELISM = 4
