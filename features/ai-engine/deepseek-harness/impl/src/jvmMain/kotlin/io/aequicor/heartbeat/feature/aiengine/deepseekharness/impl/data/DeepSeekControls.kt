package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounds control RPCs independently of their waiter. ACP operations outlive caller cancellation, so a deadline
 * retires the connection as well. Prompt execution is excluded: a working agent may run for any duration.
 */
internal class DeepSeekControls(private val scope: CoroutineScope, private val retire: suspend () -> Unit) {
    private val log = Log.tag("DeepSeekControls")

    suspend fun <T> run(operation: String, onAbandoned: (suspend (T) -> Unit)? = null, block: suspend () -> T): T {
        val answer = CompletableDeferred<T>()
        val work = scope.launch {
            try {
                answer.complete(execute(operation, block))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(EngineException(dshEngineFailure(error, null))) { "DSH control failed operation=$operation" }
                answer.completeExceptionally(error)
            }
        }
        work.invokeOnCompletion { cause ->
            if (cause != null) {
                answer.completeExceptionally(
                    EngineException(EngineFailure.Transport(TransportFailureReason.ServiceUnavailable)),
                )
            }
        }
        val caller = currentCoroutineContext()
        try {
            return answer.await()
        } finally {
            if (!caller.isActive && onAbandoned != null) releaseAbandoned(answer, onAbandoned)
        }
    }

    private suspend fun <T> execute(operation: String, block: suspend () -> T): T {
        val result = withTimeoutOrNull(DSH_CONTROL_TIMEOUT_MS) { ControlResult(block()) }
        if (result != null) return result.value
        val failure = EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
        log.w(failure) { "DSH control timed out operation=$operation; retire shared runtime" }
        // Retirement cancels our own runtime scope. Return the timeout to the independent waiter before leaving
        // this non-cancellable boundary, rather than losing the diagnosis to our own cancellation.
        withContext(NonCancellable) { retire() }
        throw failure
    }

    private fun <T> releaseAbandoned(answer: CompletableDeferred<T>, release: suspend (T) -> Unit) {
        scope.launch {
            try {
                release(answer.await())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(
                    EngineException(dshEngineFailure(error, null)),
                ) { "Abandoned DSH control has no releasable result" }
            }
        }
    }
}

private data class ControlResult<T>(val value: T)
