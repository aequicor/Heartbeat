package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark

/**
 * One fresh SDK client and user-only prompt per call. There is no session host, history, tool dispatcher,
 * hook registry, workspace, discovery or effort fallback in this path. Unsolicited tool frames end it unanswered.
 * The SDK wire boundary revalidates the exact source and deadline, enforces a protocol token cap and one send.
 */
internal class KoogIsolatedPrompts(private val access: KoogAccess) {
    private val log = Log.tag("KoogIsolatedPrompts")

    suspend fun answer(
        prompt: IsolatedPrompt,
        source: AuthSource,
        context: EngineContext,
        deadline: TimeMark,
        beforeDispatch: suspend () -> Unit,
    ): IsolatedAnswer = try {
        execute(prompt, source, context, deadline, beforeDispatch)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val causes = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val reason = causes.refusal() ?: throw e
        log.w(e.redactedFailure()) { "Strict Koog prompt refused: $reason" }
        IsolatedAnswer.Unavailable(reason)
    }

    private suspend fun execute(
        request: IsolatedPrompt,
        source: AuthSource,
        context: EngineContext,
        deadline: TimeMark,
        beforeDispatch: suspend () -> Unit,
    ): IsolatedAnswer {
        val constraints = request.constraints ?: return unavailable(IsolatedPromptFailure.IsolationUnproven)
        if (context != EngineContext(KoogEngineId, request.target.binding, model = request.target.model) ||
            request.target.engine != KoogEngineId || source.withoutLabel() != constraints.expectedSource
        ) {
            return unavailable(IsolatedPromptFailure.SourceChanged)
        }
        if (deadline.hasPassedNow()) return unavailable(IsolatedPromptFailure.Timeout)
        val provider = koogProvider(source) ?: return unavailable(IsolatedPromptFailure.Unsupported)
        val connection = access.route(context.binding)
        if (connection.binding.engine != KoogEngineId || connection.source.withoutLabel() != source.withoutLabel()) {
            return unavailable(IsolatedPromptFailure.SourceChanged)
        }
        val spec = IsolatedKoogRequest(
            provider,
            request.target.model.value,
            request.text,
            constraints.maxOutputTokens,
            deadline,
        ) {
            val current = access.route(context.binding)
            if (current.binding != connection.binding || current.source.withoutLabel() != source.withoutLabel()) {
                refuseIsolated(IsolatedPromptFailure.SourceChanged)
            }
            beforeDispatch()
        }
        var client: KoogClient? = null
        try {
            client = access.open(connection, request.target.model.value, isolated = spec)
            val input = prompt("heartbeat-model-check", LLMParams(maxTokens = constraints.maxOutputTokens)) {
                user(request.text)
            }
            if (provider == KoogProvider.Ollama) return ollamaAnswer(client, input, request)
            val text = IsolatedKoogText(request.maxAnswerChars)
            client.executor.executeStreaming(
                input,
                provider.textModel(request.target.model.value),
                emptyList(),
            ).collect { frame ->
                currentCoroutineContext().ensureActive()
                if (deadline.hasPassedNow()) refuseIsolated(IsolatedPromptFailure.Timeout)
                text.accept(frame)
            }
            return text.answer()
        } finally {
            // The facade cancels its worker without joining it. The slot remains owned until this join finishes.
            withContext(NonCancellable) {
                if (client == null) spec.lease.closeAndAwait() else client.closeAndAwait()
            }
        }
    }

    private suspend fun ollamaAnswer(client: KoogClient, input: Prompt, request: IsolatedPrompt): IsolatedAnswer {
        val response = client.executor.execute(
            input,
            KoogProvider.Ollama.textModel(request.target.model.value),
            emptyList(),
        )
        if (client.observedFinishReason() != "stop") refuseIsolated(IsolatedPromptFailure.Incomplete)
        val text = IsolatedKoogText(request.maxAnswerChars)
        for (part in response.parts) {
            when (part) {
                is MessagePart.Text -> text.accept(StreamFrame.TextDelta(part.text))

                is MessagePart.Reasoning -> Unit

                is MessagePart.Attachment, is MessagePart.Tool.Call ->
                    refuseIsolated(IsolatedPromptFailure.Incomplete)
            }
        }
        // Full SDK DTO decoding and an explicit normal terminal on the same HTTP response both succeeded.
        text.accept(StreamFrame.End("stop"))
        return text.answer()
    }
}

private class IsolatedKoogText(private val budget: Int) {
    private val blocks = mutableMapOf<Int, String>()
    private var length = 0
    private var isEnded = false

    fun accept(frame: StreamFrame) {
        if (isEnded) refuseIsolated(IsolatedPromptFailure.Incomplete)
        when (frame) {
            is StreamFrame.TextDelta -> update(frame.index, frame.text, isDelta = true)

            is StreamFrame.TextComplete -> update(frame.index, frame.text, isDelta = false)

            is StreamFrame.ToolCallDelta, is StreamFrame.ToolCallComplete ->
                refuseIsolated(IsolatedPromptFailure.Incomplete)

            is StreamFrame.End -> {
                if (frame.finishReason !in COMPLETE_REASONS) refuseIsolated(IsolatedPromptFailure.Incomplete)
                isEnded = true
            }

            is StreamFrame.ReasoningDelta, is StreamFrame.ReasoningComplete -> Unit
        }
    }

    fun answer(): IsolatedAnswer = if (isEnded) {
        IsolatedAnswer.Answered(blocks.entries.sortedBy { it.key }.joinToString("") { it.value })
    } else {
        unavailable(IsolatedPromptFailure.Incomplete)
    }

    private fun update(index: Int?, fragment: String, isDelta: Boolean) {
        val key = index ?: 0
        if (key < 0) refuseIsolated(IsolatedPromptFailure.Incomplete)
        val previous = blocks[key].orEmpty()
        val nextLength = fragment.length.toLong() + if (isDelta) previous.length else 0
        val total = length - previous.length + nextLength
        if (total > budget) refuseIsolated(IsolatedPromptFailure.ResponseTooLarge)
        val next = if (isDelta) previous + fragment else fragment
        if (next.isEmpty()) blocks.remove(key) else blocks[key] = next
        length = total.toInt()
    }
}

private fun unavailable(reason: IsolatedPromptFailure) = IsolatedAnswer.Unavailable(reason)
private val COMPLETE_REASONS = setOf("stop", "end_turn", "stop_sequence")
private const val MAX_CAUSE_DEPTH = 16

private fun refuseIsolated(reason: IsolatedPromptFailure): Nothing = throw IsolatedKoogRefusal(reason)

private fun List<Throwable>.refusal(): IsolatedPromptFailure? {
    filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    filterIsInstance<EngineException>().firstOrNull()?.let { throw it }
    return filterIsInstance<IsolatedKoogRefusal>().firstOrNull()?.reason
}
