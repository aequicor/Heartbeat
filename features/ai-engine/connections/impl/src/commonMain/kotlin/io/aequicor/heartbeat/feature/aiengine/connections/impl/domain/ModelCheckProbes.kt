package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckAspect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckIncomplete
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReport
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptConstraints
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock
import kotlin.time.TimeMark

/**
 * Executes only the two fixed probes, never saves choices, reports or chats. The owner supplies the SAME absolute
 * monotonic deadline that began before saving, including time already spent saving, queueing and checking other
 * models. Every aspect uses a fresh strict request. An obsolete run returns null. Progress is provisional, including
 * already completed aspects; consumers must discard it if the run/revision is obsolete at publication time.
 * [isCurrent] must check both the owner's run and the complete current route/selection identity without model IO.
 * The owner still revalidates publication atomically; this runner does not turn a report into admission authority.
 */
internal class ModelCheckProbes(private val prompts: IsolatedPrompts, private val clock: Clock = Clock.System) {
    private val log = Log.tag("ModelCheckProbes")

    suspend fun run(
        revision: ModelCheckRevision,
        run: ModelCheckGeneration,
        deadline: TimeMark,
        onProgress: (ModelCheckReport) -> Unit = {},
        isCurrent: suspend () -> Boolean,
    ): ModelCheckReport? {
        if (revision.definition != ModelCheckRevision.CURRENT_DEFINITION || !current(isCurrent)) return null
        onProgress(report(revision, run))
        val response = aspect(revision, deadline, false, isCurrent)
        val accuracy = response?.let {
            onProgress(report(revision, run, it))
            aspect(revision, deadline, true, isCurrent)
        }
        return if (response != null && accuracy != null && current(isCurrent)) {
            ModelCheckReport(revision, run, clock.now(), response, accuracy).also(onProgress)
        } else {
            null
        }
    }

    private fun report(
        revision: ModelCheckRevision,
        run: ModelCheckGeneration,
        response: ModelCheckAspect = ModelCheckEvaluation.incomplete(false, ModelCheckIncomplete.NotRun),
    ): ModelCheckReport = ModelCheckReport(
        revision,
        run,
        clock.now(),
        response,
        ModelCheckEvaluation.incomplete(true, ModelCheckIncomplete.NotRun),
    )

    private suspend fun aspect(
        revision: ModelCheckRevision,
        deadline: TimeMark,
        isAccuracy: Boolean,
        isCurrent: suspend () -> Boolean,
    ): ModelCheckAspect? {
        if (!current(isCurrent)) return null
        val remaining = -deadline.elapsedNow()
        if (!remaining.isPositive()) return ModelCheckEvaluation.incomplete(isAccuracy, ModelCheckIncomplete.Timeout)
        val prompt = IsolatedPrompt(
            target = revision.target,
            text = if (isAccuracy) ModelCheckEvaluation.ACCURACY_PROBE else ModelCheckEvaluation.RESPONSE_PROBE,
            timeout = remaining,
            maxAnswerChars = ModelCheckAspect.MAX_ANSWER_CHARS + 1,
            constraints = IsolatedPromptConstraints(revision.source, MAX_OUTPUT_TOKENS),
        )
        val answer = ask(prompt)
        if (!current(isCurrent)) return null
        // A misbehaving/late adapter must not turn a response arriving after the shared deadline into success.
        if (deadline.hasPassedNow()) return ModelCheckEvaluation.incomplete(isAccuracy, ModelCheckIncomplete.Timeout)
        return when (answer) {
            is IsolatedAnswer.Answered -> if (isAccuracy) {
                ModelCheckEvaluation.accuracy(answer.text)
            } else {
                ModelCheckEvaluation.response(answer.text)
            }

            is IsolatedAnswer.Unavailable -> ModelCheckEvaluation.incomplete(isAccuracy, answer.reason.checkReason())
        }
    }

    private suspend fun ask(prompt: IsolatedPrompt): IsolatedAnswer = try {
        prompts.ask(prompt)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Model check probe could not complete" }
        IsolatedAnswer.Unavailable(IsolatedPromptFailure.Failed)
    }

    private suspend fun current(isCurrent: suspend () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val isStillCurrent = isCurrent()
        currentCoroutineContext().ensureActive()
        return isStillCurrent
    }
}

private fun IsolatedPromptFailure.checkReason(): ModelCheckIncomplete = when (this) {
    IsolatedPromptFailure.Unsupported, IsolatedPromptFailure.IsolationUnproven -> ModelCheckIncomplete.IsolationUnproven

    IsolatedPromptFailure.TokenLimitUnproven -> ModelCheckIncomplete.TokenLimitUnproven

    IsolatedPromptFailure.SourceChanged -> ModelCheckIncomplete.RouteChanged

    IsolatedPromptFailure.ResponseTooLarge -> ModelCheckIncomplete.ResponseTooLarge

    IsolatedPromptFailure.Busy -> ModelCheckIncomplete.Busy

    IsolatedPromptFailure.Timeout -> ModelCheckIncomplete.Timeout

    IsolatedPromptFailure.Closing,
    IsolatedPromptFailure.Incomplete,
    IsolatedPromptFailure.Failed,
    -> ModelCheckIncomplete.Unavailable
}

private const val MAX_OUTPUT_TOKENS = 128
