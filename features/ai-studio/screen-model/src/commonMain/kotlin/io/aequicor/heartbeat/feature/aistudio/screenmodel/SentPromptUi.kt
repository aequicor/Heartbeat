@file:UseSerializers(ImmutableListSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * A composer message sent to chat [sessionId] that the chat's transcript does not show yet. Engines echo the user's
 * message into their history late (Claude Code only at the end of the turn), so the feed draws this copy after the
 * transcript until the transcript holds more than [ordinal] prompts: the echo then takes its place in the same state.
 *
 * [ordinal] is the number of prompts the chat showed when the message was sent, plus the sent prompts still waiting
 * before it. [isRunEnded] marks a turn that ended before its echo: a turn that failed may never record the prompt,
 * so the copy leaves with the chat's next transcript change or its next sent message.
 */
@Serializable
@SerialName("SentPromptUi")
@Immutable
data class SentPromptUi(
    val sessionId: String,
    val text: String,
    val attachments: ImmutableList<AttachmentUi>,
    val ordinal: Int,
    val isRunEnded: Boolean = false,
) {
    override fun toString(): String = "SentPromptUi(ordinal=$ordinal, files=${attachments.size}, ended=$isRunEnded)"
}

/**
 * Starts showing [submission] in the feed of its chat. A new chat's submission has no chat until its session is
 * created, so it is shown from [prepareSubmission]; organism chats show their cells and are left alone.
 */
internal fun AiStudioScreenState.withSentPrompt(id: String, submission: SubmissionUi): AiStudioScreenState {
    val chat = submission.sessionId ?: return this
    if (id in sentPrompts || sessions.any { it.id == chat && it.isOrganism }) return this
    val waiting = sentPrompts.filterValues { it.sessionId != chat || !it.isRunEnded }
    val ordinal = transcripts[chat].promptCount() + waiting.values.count { it.sessionId == chat }
    val sent = SentPromptUi(chat, submission.text.trim(), submission.attachments, ordinal)
    return copy(sentPrompts = (waiting + (id to sent)).toImmutableMap())
}

/** A refused or failed submission no longer appears in the feed; its draft returns to the composer instead. */
internal fun AiStudioScreenState.withoutSentPrompt(id: String): AiStudioScreenState =
    if (id in sentPrompts) copy(sentPrompts = (sentPrompts - id).toImmutableMap()) else this

/** The turn of [sessionId] ended: its prompts not echoed yet leave with the chat's next transcript change. */
fun AiStudioScreenState.endSentPrompts(sessionId: String): AiStudioScreenState {
    if (sentPrompts.values.none { it.sessionId == sessionId && !it.isRunEnded }) return this
    return copy(
        sentPrompts = sentPrompts.mapValues { (_, sent) ->
            if (sent.sessionId == sessionId) sent.copy(isRunEnded = true) else sent
        }.toImmutableMap(),
    )
}

/**
 * Replaces the open transcripts with [next]. Sent prompts the transcript of their chat now shows leave, and so do
 * those of an ended turn once their chat's transcript changed; a closed chat keeps its prompts until it reopens.
 */
fun AiStudioScreenState.withTranscripts(next: ImmutableMap<String, ImmutableList<MessageUi>>): AiStudioScreenState {
    if (sentPrompts.isEmpty()) return copy(transcripts = next)
    val kept = sentPrompts.filterValues { sent ->
        val messages = next[sent.sessionId]
        val isEchoed = messages != null && messages.promptCount() > sent.ordinal
        // Lists of unchanged chats are carried over as the same instances.
        val isChanged = messages !== transcripts[sent.sessionId]
        !isEchoed && !(sent.isRunEnded && isChanged)
    }
    val remaining = if (kept.size == sentPrompts.size) sentPrompts else kept.toImmutableMap()
    return copy(transcripts = next, sentPrompts = remaining)
}

/**
 * [messages] of chat [sessionId] followed by its sent prompts they do not show yet, in send order; a chat whose
 * transcript is not loaded yet shows only those prompts.
 */
fun AiStudioScreenState.withSentPrompts(
    sessionId: String,
    messages: ImmutableList<MessageUi>?,
): ImmutableList<MessageUi>? {
    if (sentPrompts.values.none { it.sessionId == sessionId }) return messages
    val shown = messages.promptCount()
    val waiting = sentPrompts.entries
        .filter { (_, sent) -> sent.sessionId == sessionId && sent.ordinal >= shown }
        .sortedBy { it.value.ordinal }
    if (waiting.isEmpty()) return messages
    val createdAt = messages?.lastOrNull()?.createdAt ?: now
    val prompts = waiting.map { (id, sent) ->
        MessageUi.Prompt(SENT_PROMPT_PREFIX + id, createdAt, sent.text, isTimestampKnown = false, sent.attachments)
    }
    return (messages.orEmpty() + prompts).toImmutableList()
}

private fun List<MessageUi>?.promptCount(): Int = this?.count { it is MessageUi.Prompt } ?: 0

/** Feed ids of sent prompts, distinct from every transcript entry. */
private const val SENT_PROMPT_PREFIX = "sent:"
