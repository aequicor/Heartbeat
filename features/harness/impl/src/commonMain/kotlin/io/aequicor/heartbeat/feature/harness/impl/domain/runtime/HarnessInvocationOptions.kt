package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job

/** Trusted dispatch metadata. The observer runs before execution starts and must not execute author code. */
internal data class HarnessInvocationOptions(
    val origin: HarnessCallOrigin = HarnessCallOrigin(),
    val lifetime: Job? = null,
    val onStarted: (HarnessInvocationHandle) -> Unit = {},
) {
    override fun toString(): String = "HarnessInvocationOptions(***)"
}

/** Actual execution ownership; cancellation is only a request, while awaitStopped waits for actual completion. */
internal class HarnessInvocationHandle internal constructor(private val job: Job, private val onCancel: () -> Unit) {
    private val log = Log.tag("HarnessInvocation")
    val isStopped: Boolean get() = job.isCompleted

    @HighFrequency
    fun cancel() {
        log.v { "cancel actual script invocation" }
        onCancel()
        job.cancel()
    }

    suspend fun awaitStopped() = job.join()

    /** Host bookkeeping only; completion observers must never execute author code. */
    fun invokeOnStopped(block: () -> Unit): DisposableHandle = job.invokeOnCompletion { block() }
}
