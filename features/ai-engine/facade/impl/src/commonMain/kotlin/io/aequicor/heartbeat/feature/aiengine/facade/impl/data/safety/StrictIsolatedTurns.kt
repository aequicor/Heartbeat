package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Strict adapter invocation; never falls back to a normal service session. */
internal fun interface StrictPromptExecution {
    suspend fun answer(prompt: IsolatedPrompt, deadline: TimeMark): IsolatedAnswer
}

/**
 * Queue and execution share the caller's deadline. Profile-owned workers retain their capacity until adapter
 * cleanup actually returns; caller cancellation/timeout cancels work without joining potentially slow cleanup.
 * The answer deferred has no profile parent, so late completion cannot escape to a dismissed caller.
 */
internal class StrictIsolatedTurns(
    private val scope: CoroutineScope,
    private val slots: ServiceTurnSlots,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val execution: StrictPromptExecution,
) {
    private val log = Log.tag("StrictIsolatedTurns")

    suspend fun run(prompt: IsolatedPrompt): IsolatedAnswer {
        requireNotNull(prompt.constraints)
        if (!scope.isActive) return unavailable(IsolatedPromptFailure.Closing)
        val started = timeSource.markNow()
        val answer = CompletableDeferred<IsolatedAnswer>()
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                answer.complete(execute(prompt, started + prompt.timeout))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Provider exceptions can contain prompt or credential material.
                log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "Strict isolated prompt failed" }
                answer.complete(unavailable(IsolatedPromptFailure.Failed))
            }
        }
        val completion = worker.invokeOnCompletion { answer.complete(unavailable(IsolatedPromptFailure.Closing)) }
        return try {
            withTimeoutOrNull(prompt.timeout) {
                worker.start()
                answer.await()
            } ?: unavailable(if (scope.isActive) IsolatedPromptFailure.Timeout else IsolatedPromptFailure.Closing)
        } finally {
            answer.cancel()
            worker.cancel()
            completion.dispose()
        }
    }

    private suspend fun execute(prompt: IsolatedPrompt, deadline: TimeMark): IsolatedAnswer {
        var isSlotOwned = false
        return try {
            withTimeoutOrNull(-deadline.elapsedNow()) {
                isSlotOwned = slots.acquire()
                if (!isSlotOwned) return@withTimeoutOrNull unavailable(IsolatedPromptFailure.Busy)
                currentCoroutineContext().ensureActive()
                // The caller dispatcher may not yet have processed its timeout or cancellation.
                if (deadline.hasPassedNow()) return@withTimeoutOrNull unavailable(IsolatedPromptFailure.Timeout)
                val result = coroutineScope { execution.answer(prompt, deadline) }
                currentCoroutineContext().ensureActive()
                if (deadline.hasPassedNow()) unavailable(IsolatedPromptFailure.Timeout) else result
            } ?: unavailable(IsolatedPromptFailure.Timeout)
        } finally {
            if (isSlotOwned) slots.releaseRunning()
        }
    }
}

private fun unavailable(reason: IsolatedPromptFailure) = IsolatedAnswer.Unavailable(reason)
