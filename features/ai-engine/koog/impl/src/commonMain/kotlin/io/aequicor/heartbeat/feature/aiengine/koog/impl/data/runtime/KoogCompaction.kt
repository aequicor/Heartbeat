package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.sessionEssence
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogContextSummary
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

private val log = Log.tag("KoogCompaction")

/** Koog summarizes its own transcript, so focus instructions are always honored. */
internal object KoogCompactsContext : CompactsContext {
    override val isInstructionsSupported: Boolean = true
}

/** Text of the compaction notice for consumers that do not know its kind. */
internal const val KOOG_COMPACTED_NOTICE = "Context compacted"

/** Largest transcript essence sent to the summarizer; the newest entries win. */
internal const val KOOG_COMPACTION_INPUT_CHARS = 120_000

/** Items the model still sees: the transcript after the last compaction boundary. */
internal fun List<SessionItem>.koogContextItems(context: KoogContextSummary?): List<SessionItem> {
    val boundary = context?.boundary ?: return this
    val index = indexOfFirst { it.info.id == boundary }
    return if (index < 0) this else drop(index + 1)
}

/** System block that replaces the transcript before the boundary. */
internal fun koogSummaryBlock(context: KoogContextSummary): String =
    "Summary of the earlier conversation, which is no longer shown:\n\n${context.summary}"

/** The notice that marks the boundary; positioned after the current transcript of [turn]. */
internal fun koogCompactedNotice(position: Int, turn: TurnId): SessionItem.Notice = SessionItem.Notice(
    ItemInfo(ItemId(Uuid.random().toString()), position.toLong(), 0, turn),
    KOOG_COMPACTED_NOTICE,
    NoticeKind.ContextCompacted,
)

/** Summarization request over the visible context, the previous summary and optional focus instructions. */
internal fun koogCompactionPrompt(
    items: List<SessionItem>,
    context: KoogContextSummary?,
    compaction: ContextCompaction,
): Prompt = prompt("heartbeat-compaction", LLMParams()) {
    system(SUMMARY_INSTRUCTIONS)
    user(
        buildString {
            context?.let { append("Earlier summary:\n").append(it.summary).append("\n\n") }
            append("Conversation since then:\n")
            append(sessionEssence(items.koogContextItems(context), KOOG_COMPACTION_INPUT_CHARS).text)
            compaction.instructions?.let { append("\n\nFocus requested by the user: ").append(it) }
        },
    )
}

/**
 * Runs the summary without tools and hands it to [compacted]. Failures settle the turn as failed; the transcript
 * and its context stay unchanged. Cancellation propagates.
 */
internal suspend fun KoogClient.koogCompaction(
    model: LLModel,
    input: Prompt,
    compacted: suspend (String) -> Unit,
): TurnOutcome = try {
    log.i { "Summarizing Koog context model=${model.id}" }
    val content = KoogStreamParts()
    var isEnded = false
    executor.executeStreaming(input, model, emptyList()).collect { frame ->
        if (frame is StreamFrame.End) isEnded = true
        content.append(frame)
    }
    val summary = content.text.trim()
    if (!isEnded || summary.isEmpty()) fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    compacted(summary)
    log.i { "Koog context compacted: summary=${summary.length} chars" }
    TurnOutcome.Completed
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.w(e.sanitized()) { "Koog compaction failed" }
    TurnOutcome.Failed(e.sanitized().failure)
}

private const val SUMMARY_INSTRUCTIONS =
    "You compress a conversation between a user and an AI assistant so that the assistant can continue the work " +
        "without the original messages. Keep the user's goals and constraints, decisions and their reasons, the " +
        "current state of the work (files changed, commands run and their results), unresolved problems, the next " +
        "steps and facts that will be needed later. Drop greetings, superseded attempts and verbatim tool output. " +
        "Write concise Markdown in the language of the conversation, at most about 1500 words. Output only the summary."
