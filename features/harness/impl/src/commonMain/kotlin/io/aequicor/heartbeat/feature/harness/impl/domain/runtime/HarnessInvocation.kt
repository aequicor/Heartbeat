package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Logical wait outcome. Actual execution can outlive [TimedOut] until blocked code really returns. */
internal sealed interface HarnessInvocationResult<out T> {
    data class Completed<T>(val value: T) : HarnessInvocationResult<T> {
        override fun toString(): String = "HarnessInvocationResult.Completed(***)"
    }

    data object TimedOut : HarnessInvocationResult<Nothing>
}

/** Fixed author-code budgets, measured by a control dispatcher outside the constrained execution lane. */
internal object HarnessInvocationBudget {
    val Evaluation: Duration = 10.seconds
    val Event: Duration = 30.seconds
    val Tool: Duration = 60.seconds
    val Instructions: Duration = 500.milliseconds
}

/**
 * Tracks actual calls independently of their waiters. [ownerScope] must be an instance/profile-owned supervised
 * scope with a fatal-error handler. Callers wrap ordinary script failures in the sanitized boundary; uncaught
 * failures from actual execution always reach the owner handler, including after a waiter timed out.
 * The higher instance owns admission, artifact
 * leases and the sanitized error boundary. It closes admission before [awaitIdle] or [cancelAll]. Neither a
 * timeout nor waiter cancellation proves execution has stopped; each job remains tracked until completion.
 */
internal class HarnessInvocation(
    private val ownerScope: CoroutineScope,
    private val controlDispatcher: CoroutineDispatcher,
) {
    private val jobs = MutableStateFlow<Set<Job>>(emptySet())
    private val log = Log.tag("HarnessInvocation")

    val activeCount: Int get() = jobs.value.size

    @OptIn(ExperimentalCoroutinesApi::class)
    @HighFrequency
    suspend fun <T> run(
        dispatcher: CoroutineDispatcher,
        timeout: Duration,
        block: suspend () -> T,
    ): HarnessInvocationResult<T> {
        require(timeout.isPositive() && timeout.isFinite())
        log.v { "invoke harness callback" }
        val result = CompletableDeferred<HarnessInvocationResult.Completed<T>>()
        var produced: HarnessInvocationResult.Completed<T>? = null
        val task = ownerScope.launch(dispatcher, start = CoroutineStart.LAZY) {
            produced = HarnessInvocationResult.Completed(block())
        }
        // Register before the first suspension so retirement also sees calls awaiting the control dispatcher.
        jobs.update { it + task }
        task.invokeOnCompletion { error ->
            jobs.update { current -> current - task }
            if (error == null) result.complete(checkNotNull(produced)) else result.completeExceptionally(error)
        }
        try {
            return withContext(controlDispatcher) {
                task.start()
                select {
                    result.onAwait { it }
                    onTimeout(timeout) { HarnessInvocationResult.TimedOut }
                }
            }
        } finally {
            // Cancellation is a request only: a blocked task stays in jobs until its completion handler runs.
            task.cancel()
        }
    }

    @HighFrequency
    fun cancelAll() {
        log.v { "cancel owned harness callbacks" }
        jobs.value.forEach { it.cancel() }
    }

    /** Caller first retires admission; waiting never frees a lane slot or an artifact before actual completion. */
    suspend fun awaitIdle() {
        var current = jobs.value
        while (current.isNotEmpty()) {
            current.joinAll()
            current = jobs.value
        }
    }
}
