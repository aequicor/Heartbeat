package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckIncomplete
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckOutcome
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReport
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Execution boundary for one accepted save and all its selected model checks. The profile owns the worker and any
 * unfinished cleanup. Caller cancellation/timeout cancels it without joining. An accepted durable save is allowed to
 * finish, but cannot start late probes or publish a late result; the selection observer reconciles a late commit.
 * Nothing here persists reports. The machine owner must validate its operation ID and each report's current run
 * before guarded publication. [scope] must be the profile scope, never an app/global scope or the caller's UI job.
 */
internal class ModelCheckBatch(
    private val scope: CoroutineScope,
    private val probes: ModelCheckProbes,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private val log = Log.tag("ModelCheckBatch")

    suspend fun <T : Any> execute(
        request: ModelCheckBatchRequest<T>,
        budget: Duration = 5.seconds,
    ): ModelCheckBatchResult<T> {
        require(budget.isFinite() && budget.isPositive()) { "Model check batch needs a finite positive budget" }
        currentCoroutineContext().ensureActive()
        val deadline = time.markNow() + budget
        val saved = MutableStateFlow<T?>(null)
        val progress = MutableStateFlow<List<ModelCheckReport>>(emptyList())
        val answer = CompletableDeferred<ModelCheckBatchResult<T>>()
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val value = withContext(NonCancellable) { request.save() }
                saved.value = value
                currentCoroutineContext().ensureActive()
                val reports = check(request, value, deadline, progress)
                currentCoroutineContext().ensureActive()
                val result = if (deadline.hasPassedNow()) {
                    ModelCheckBatchResult.TimedOut(value, progress.value.timedOut())
                } else {
                    ModelCheckBatchResult.Completed(value, reports)
                }
                answer.complete(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error.redactedFailure()) { "Model check batch could not complete" }
                answer.complete(ModelCheckBatchResult.Failed(saved.value, progress.value))
            }
        }
        worker.invokeOnCompletion { answer.cancel() }
        worker.start()
        return try {
            val remaining = -deadline.elapsedNow()
            if (remaining.isPositive()) {
                val result = withTimeoutOrNull(remaining) { answer.await() }
                if (result == null || deadline.hasPassedNow()) {
                    ModelCheckBatchResult.TimedOut(saved.value, progress.value.timedOut())
                } else {
                    result
                }
            } else {
                ModelCheckBatchResult.TimedOut(saved.value, progress.value.timedOut())
            }
        } finally {
            // The profile worker retains ownership until its noncancellable save/adapter cleanup really settles.
            worker.cancel()
        }
    }

    private suspend fun <T : Any> check(
        request: ModelCheckBatchRequest<T>,
        saved: T,
        deadline: TimeMark,
        progress: MutableStateFlow<List<ModelCheckReport>>,
    ): List<ModelCheckReport> {
        if (deadline.hasPassedNow()) return emptyList()
        val reports = mutableListOf<ModelCheckReport>()
        for (revision in request.revisions(saved).distinctBy { it.target }) {
            currentCoroutineContext().ensureActive()
            if (deadline.hasPassedNow()) break
            val report = probes.run(revision, request.run, deadline, onProgress = { latest ->
                progress.value = progress.value.filterNot { it.revision.target == revision.target } + latest
            }) { request.isCurrent(revision) }
            if (report == null) progress.value = progress.value.filterNot { it.revision.target == revision.target }
            if (report != null) reports += report
        }
        return reports
    }
}

/** Internal IO ports for the two existing machine effect handlers, not a new public connections service. */
internal data class ModelCheckBatchRequest<T : Any>(
    val run: ModelCheckGeneration,
    val save: suspend () -> T,
    val revisions: suspend (T) -> List<ModelCheckRevision>,
    val isCurrent: suspend (ModelCheckRevision) -> Boolean,
) {
    override fun toString(): String = "ModelCheckBatchRequest"
}

/** Transient result. A null [ModelCheckBatchResult.TimedOut.saved] does not establish rollback. */
internal sealed interface ModelCheckBatchResult<out T : Any> {
    data class Completed<T : Any>(val saved: T, val reports: List<ModelCheckReport>) : ModelCheckBatchResult<T> {
        override fun toString(): String = "ModelCheckBatchResult.Completed"
    }

    data class TimedOut<T : Any>(val saved: T?, val reports: List<ModelCheckReport>) : ModelCheckBatchResult<T> {
        override fun toString(): String = "ModelCheckBatchResult.TimedOut"
    }

    data class Failed<T : Any>(val saved: T?, val reports: List<ModelCheckReport>) : ModelCheckBatchResult<T> {
        override fun toString(): String = "ModelCheckBatchResult.Failed"
    }
}

private fun List<ModelCheckReport>.timedOut(): List<ModelCheckReport> = map { report ->
    fun pending(isAccuracy: Boolean) = ModelCheckEvaluation.incomplete(isAccuracy, ModelCheckIncomplete.Timeout)
    val notRun = ModelCheckOutcome.Incomplete(ModelCheckIncomplete.NotRun)
    report.copy(
        response = if (report.response.outcome == notRun) pending(false) else report.response,
        accuracy = if (report.accuracy.outcome == notRun) pending(true) else report.accuracy,
    )
}
