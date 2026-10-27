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
import kotlin.time.Instant

/**
 * A composer message sent to chat [sessionId] that the chat's transcript does not show yet. Engines echo the user's
 * message into their history late (Claude Code only at the end of the turn), so the feed draws this copy until the
 * transcript holds more than [ordinal] prompts: the echo then takes its place in the same state.
 *
 * [ordinal] is the number of prompts the chat showed when the message was sent, plus the sent prompts still waiting
 * before it. [position] is the transcript size at that moment and [shownPrompts] its prompt count: entries the turn
 * streams before the echo come after the copy, so it is drawn at [position], moved past the echoes of earlier sent
 * prompts that arrived since, and the echo lands where the copy was. [sentAt] dates the copy in a chat without
 * earlier entries. [isRunEnded] marks a turn that ended before its echo: a turn that failed may never record
 * the prompt, so the copy leaves with the chat's next transcript change, once the chat closes, or with its next sent
 * message.
 */
@Serializable
@SerialName("SentPromptUi")
@Immutable
data class SentPromptUi(
    val sessionId: String,
    val text: String,
    val attachments: ImmutableList<AttachmentUi>,
    val ordinal: Int,
    val position: Int,
    val shownPrompts: Int,
    val sentAt: Instant,
    val isRunEnded: Boolean = false,
) {
    override fun toString(): String = "SentPromptUi(ordinal=$ordinal, position=$position, shown=$shownPrompts, " +
        "files=${attachments.size}, ended=$isRunEnded)"
}

/**
 * Starts showing [submission] in the feed of its chat. A new chat's submission has no chat until its session is
 * created, so it is shown from [prepareSubmission] ([isNewChat]), organism chats too (their zygote echoes the goal only
 * when its first turn starts); an existing organism chat shows its cells and is left alone. An existing chat whose transcript has not loaded yet gets no copy: its first history could not be told
 * apart from the echo.
 */
internal fun AiStudioScreenState.withSentPrompt(
    id: String,
    submission: SubmissionUi,
    isNewChat: Boolean,
): AiStudioScreenState {
    val chat = submission.sessionId ?: return this
    if (id in sentPrompts || (!isNewChat && sessions.any { it.id == chat && it.isOrganism })) return this
    val messages = transcripts[chat]
    if (messages == null && !isNewChat) return this
    val waiting = sentPrompts.filterValues { it.sessionId != chat || !it.isRunEnded }
    val shown = messages.promptCount()
    val ordinal = shown + waiting.values.count { it.sessionId == chat }
    val sent = SentPromptUi(
        sessionId = chat,
        text = submission.text.trim(),
        attachments = submission.attachments,
        ordinal = ordinal,
        position = messages?.size ?: 0,
        shownPrompts = shown,
        sentAt = now,
    )
    return copy(sentPrompts = (waiting + (id to sent)).toImmutableMap())
}

/** A refused or failed submission no longer appears in the feed; its draft returns to the composer instead. */
internal fun AiStudioScreenState.withoutSentPrompt(id: String): AiStudioScreenState =
    if (id in sentPrompts) copy(sentPrompts = (sentPrompts - id).toImmutableMap()) else this

/**
 * The turn of [sessionId] ended: its prompts not echoed yet leave with the chat's next transcript change. An organism
 * chat's run ends as soon as the organism accepts the goal, long before its zygote echoes it, so its copy stays.
 */
fun AiStudioScreenState.endSentPrompts(sessionId: String): AiStudioScreenState {
    if (sessions.any { it.id == sessionId && it.isOrganism }) return this
    if (sentPrompts.values.none { it.sessionId == sessionId && !it.isRunEnded }) return this
    return copy(
        sentPrompts = sentPrompts.mapValues { (_, sent) ->
            if (sent.sessionId == sessionId) sent.copy(isRunEnded = true) else sent
        }.toImmutableMap(),
    )
}

/**
 * Replaces the open transcripts with [next]. Sent prompts the transcript of their chat now shows leave. Those of an
 * ended turn also leave once their chat's messages differ, or once the chat is not open; a closed chat keeps the
 * prompts of a running turn until it reopens or the turn ends.
 */
fun AiStudioScreenState.withTranscripts(next: ImmutableMap<String, ImmutableList<MessageUi>>): AiStudioScreenState {
    if (sentPrompts.isEmpty()) return copy(transcripts = next)
    val kept = sentPrompts.filterValues { sent ->
        val messages = next[sent.sessionId]
        val isEchoed = messages != null && messages.promptCount() > sent.ordinal
        // Runtime-only updates re-emit equal lists; only a real change may drop the copy of an ended turn.
        val isGone = sent.isRunEnded && (messages == null || messages != transcripts[sent.sessionId])
        !isEchoed && !isGone
    }
    val remaining = if (kept.size == sentPrompts.size) sentPrompts else kept.toImmutableMap()
    return copy(transcripts = next, sentPrompts = remaining)
}

/**
 * [messages] of chat [sessionId] with its sent prompts they do not show yet, each at its place
 * ([SentPromptUi.position]) in send order; a chat whose transcript is not loaded yet shows only those prompts.
 */
fun AiStudioScreenState.withSentPrompts(
    sessionId: String,
    messages: ImmutableList<MessageUi>?,
): ImmutableList<MessageUi>? {
    if (sentPrompts.values.none { it.sessionId == sessionId }) return messages
    val shown = messages.promptCount()
    val history = messages.orEmpty()
    val waiting = sentPrompts.entries
        .filter { (_, sent) -> sent.sessionId == sessionId && sent.ordinal >= shown }
        .sortedBy { it.value.ordinal }
    if (waiting.isEmpty()) return messages
    val feed = ArrayList<MessageUi>(history.size + waiting.size)
    var copied = 0
    for ((id, sent) in waiting) {
        // Prompts that arrived since the send are echoes of earlier sent prompts drawn before this one.
        val arrived = (shown - sent.shownPrompts).coerceAtLeast(0)
        val at = (sent.position + arrived).coerceIn(copied, history.size)
        feed.addAll(history.subList(copied, at))
        copied = at
        val createdAt = history.getOrNull(at - 1)?.createdAt ?: sent.sentAt
        feed += MessageUi.Prompt(
            SENT_PROMPT_PREFIX + id,
            createdAt,
            sent.text,
            isTimestampKnown = false,
            sent.attachments,
        )
    }
    feed.addAll(history.subList(copied, history.size))
    return feed.toImmutableList()
}

/**
 * What [pane] is sending while its new chat is created: there is no chat to carry the message yet, but the user's
 * message must not wait for the worktree or sandbox to be prepared. Null once the pane has its chat (its feed then
 * draws [SentPromptUi]) or when nothing is being sent.
 */
fun AiStudioScreenState.creatingPrompts(pane: PaneUi): ImmutableList<MessageUi>? {
    if (!pane.isCreating || pane.sessionId != null) return null
    return submissions.values.filter { it.paneId == pane.id && it.sessionId == null }
        .mapIndexed { index, sent ->
            MessageUi.Prompt(
                "$CREATING_PROMPT_PREFIX${pane.id}:$index",
                now,
                sent.text.trim(),
                isTimestampKnown = false,
                sent.attachments,
            )
        }.takeIf { it.isNotEmpty() }?.toImmutableList()
}

private fun List<MessageUi>?.promptCount(): Int = this?.count { it is MessageUi.Prompt } ?: 0

/** Feed ids of sent prompts, distinct from every transcript entry. */
private const val SENT_PROMPT_PREFIX = "sent:"

/** Feed ids of messages shown while their chat is created. */
private const val CREATING_PROMPT_PREFIX = "creating:"
