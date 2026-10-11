package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /** Author cancellation is a failed callback; host/lifetime shutdown is ordinary revocation. */
    data class Cancelled(val isExpected: Boolean) : HarnessInvocationResult<Nothing>
}

/**
 * Fixed author-code budgets, measured by a control dispatcher outside the constrained execution lane from the
 * moment the callback actually starts running on it.
 */
internal object HarnessInvocationBudget {
    val Evaluation: Duration = 10.seconds
    val Event: Duration = 30.seconds
    val Tool: Duration = 60.seconds
    val Instructions: Duration = 500.milliseconds

    /** Longest continuous worker hold by an instance's background code before it counts as the instance's fault. */
    val Background: Duration = 30.seconds
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
    private val origins: HarnessCallOrigins? = null,
) {
    private val jobs = MutableStateFlow<Map<Job, HarnessInvocationHandle>>(emptyMap())
    private val log = Log.tag("HarnessInvocation")

    val activeCount: Int get() = jobs.value.size

    @OptIn(ExperimentalCoroutinesApi::class)
    @HighFrequency
    suspend fun <T> run(
        dispatcher: CoroutineDispatcher,
        timeout: Duration,
        options: HarnessInvocationOptions = HarnessInvocationOptions(),
        block: suspend () -> T,
    ): HarnessInvocationResult<T> {
        require(timeout.isPositive() && timeout.isFinite())
        log.v { "invoke harness callback" }
        val result = CompletableDeferred<HarnessInvocationResult<T>>()
        val hostCancellation = MutableStateFlow(false)
        var produced: HarnessInvocationResult.Completed<T>? = null
        val inherited = currentCoroutineContext()[HarnessOriginContext]?.origin ?: HarnessCallOrigin()
        val origin = inherited.merge(options.origin)
        val context = origins?.context(origin) ?: HarnessOriginContext(origin)
        val started = CompletableDeferred<Unit>()
        val budget = HarnessCallBudget(timeout)
        val task = ownerScope.launch(dispatcher + context + budget, start = CoroutineStart.LAZY) {
            started.complete(Unit)
            currentCoroutineContext().ensureActive()
            if (options.lifetime?.isActive == false) throw CancellationException("Script call lifetime ended")
            produced = HarnessInvocationResult.Completed(block())
        }
        // Register before the first suspension so retirement also sees calls awaiting the control dispatcher.
        val handle = HarnessInvocationHandle(task) {
            hostCancellation.value = true
            result.complete(HarnessInvocationResult.Cancelled(isExpected = true))
        }
        jobs.update { it + (task to handle) }
        val lifetime = bindLifetime(options.lifetime, task) {
            hostCancellation.value = true
            result.complete(HarnessInvocationResult.Cancelled(isExpected = true))
        }
        task.invokeOnCompletion { error ->
            lifetime?.complete()
            jobs.update { current -> current - task }
            when (error) {
                null -> result.complete(checkNotNull(produced))

                is CancellationException -> result.complete(
                    HarnessInvocationResult.Cancelled(
                        hostCancellation.value || options.lifetime?.isActive == false ||
                            ownerScope.coroutineContext[Job]?.isActive == false,
                    ),
                )

                else -> result.completeExceptionally(error)
            }
        }
        try {
            options.onStarted(handle)
            return withContext(controlDispatcher) {
                task.start()
                val outcome = awaitStart(dispatcher, started, result, timeout) ?: select {
                    result.onAwait { it }
                    onTimeout(timeout) { HarnessInvocationResult.TimedOut }
                }
                // Code still running after its waiter gave up is overdue for calls queued behind it.
                if (outcome is HarnessInvocationResult.TimedOut) budget.expire()
                outcome
            }
        } finally {
            lifetime?.complete()
            // Cancellation is a request only: a blocked task stays in jobs until its completion handler runs.
            task.cancel()
        }
    }

    /**
     * Waiting for the shared lane is not author execution: the budget starts only once the task actually runs, so
     * other harnesses' blocked callbacks cannot time out (and eventually disable) this one. A call that never
     * started within a further [timeout] is an expected revocation while foreign work holds the lane or while the
     * instance's own call or background work runs within its budget (a legitimately long tool call must not fail
     * the short calls queued behind it). Only when the instance's own work holding its serialized worker is
     * overdue is it this instance's timeout, counted as a failure.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> awaitStart(
        dispatcher: CoroutineDispatcher,
        started: CompletableDeferred<Unit>,
        result: CompletableDeferred<HarnessInvocationResult<T>>,
        timeout: Duration,
    ): HarnessInvocationResult<T>? = select {
        result.onAwait { it }
        started.onAwait { null }
        onTimeout(timeout) { notStarted(dispatcher) }
    }

    /** One warning per exhausted lane period; repeated starvation and self-blocked calls stay verbose. */
    @HighFrequency
    private fun notStarted(dispatcher: CoroutineDispatcher): HarnessInvocationResult<Nothing> {
        val lane = dispatcher as? HarnessLaneDispatcher
        return when (lane?.occupancy) {
            HarnessLaneOccupancy.Overdue -> {
                log.v { "callback never started behind overdue work of its own instance" }
                HarnessInvocationResult.TimedOut
            }

            HarnessLaneOccupancy.WithinBudget -> {
                log.v { "callback never started behind in-budget work of its own instance" }
                HarnessInvocationResult.Cancelled(isExpected = true)
            }

            HarnessLaneOccupancy.Idle, null -> {
                if (lane?.markStarved() != false) {
                    log.w { "Harness execution lane stayed exhausted; the callback never started" }
                } else {
                    log.v { "harness execution lane still exhausted; the callback never started" }
                }
                HarnessInvocationResult.Cancelled(isExpected = true)
            }
        }
    }

    /**
     * A child watcher is cancelled as soon as the lifetime starts cancelling, without waiting for unrelated blocked
     * cleanup children of the lifetime; an already ended lifetime cancels it immediately. The waiter completes the
     * watcher when it stops waiting, so the watcher never outlives the bounded wait.
     */
    private fun bindLifetime(lifetime: Job?, task: Job, onCancelled: () -> Unit): CompletableJob? =
        lifetime?.let { Job(it) }?.also { watcher ->
            watcher.invokeOnCompletion { cause ->
                if (cause != null) {
                    onCancelled()
                    task.cancel()
                }
            }
        }

    @HighFrequency
    fun cancelAll() {
        log.v { "cancel owned harness callbacks" }
        jobs.value.values.forEach { it.cancel() }
    }

    /** Caller first retires admission; waiting never frees a lane slot or an artifact before actual completion. */
    suspend fun awaitIdle() {
        var current = jobs.value
        while (current.isNotEmpty()) {
            current.keys.toList().joinAll()
            current = jobs.value
        }
    }
}
