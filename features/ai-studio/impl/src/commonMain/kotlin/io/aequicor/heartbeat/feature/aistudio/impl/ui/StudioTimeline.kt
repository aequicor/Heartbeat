package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbChatSection
import io.aequicor.heartbeat.ds.components.HbChatTimeline
import io.aequicor.heartbeat.ds.components.HbMessageAppearance
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.components.HbToolBlock
import io.aequicor.heartbeat.ds.components.HbToolCall
import io.aequicor.heartbeat.ds.components.HbToolKind
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration

/**
 * Localized copy of a transcript. Templates keep their `%1$s` / `%1$d` placeholders; [fill] substitutes them,
 * so the timeline can be prepared outside composition.
 */
@Immutable
internal data class TimelineLabels(
    val section: String,
    val you: String,
    val agent: String,
    val studio: String,
    val stoppedTemplate: String,
    val failed: FailureLabels,
    val durations: DurationLabels,
    val promptAppearance: HbMessageAppearance = HbMessageAppearance(tone = HbTone.Brand),
    val replyAppearance: HbMessageAppearance = HbMessageAppearance(),
    val reasoning: String = "Reasoning",
    val today: String = "Today",
    val yesterday: String = "Yesterday",
    val calendar: StudioCalendar = StudioCalendar(),
    val isGroupedByDate: Boolean = false,
    val feedback: FeedbackLabels? = null,
)

/** Failed-run notices, one per [FailureUi]. */
@Immutable
internal data class FailureLabels(
    val unknown: String,
    val limit: String = unknown,
    val context: String = unknown,
    val authentication: String = unknown,
    val network: String = unknown,
) {
    fun of(kind: FailureUi): String = when (kind) {
        FailureUi.Limit -> limit
        FailureUi.Context -> context
        FailureUi.Authentication -> authentication
        FailureUi.Network -> network
        FailureUi.Unknown -> unknown
    }
}

/** Templates of elapsed-time labels: seconds only, and minutes with seconds. */
@Immutable
internal data class DurationLabels(val secondsTemplate: String, val minutesTemplate: String) {
    /** Whole seconds below a minute, minutes and seconds above. */
    fun format(duration: Duration): String {
        val total = duration.inWholeSeconds.coerceAtLeast(0)
        return if (total < SECONDS_PER_MINUTE) {
            fill(secondsTemplate, total)
        } else {
            fill(minutesTemplate, total / SECONDS_PER_MINUTE, total % SECONDS_PER_MINUTE)
        }
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}

/** Substitutes positional `%n$s` / `%n$d` placeholders of a resource template. */
internal fun fill(template: String, vararg args: Any): String = args.foldIndexed(template) { index, text, arg ->
    text.replace("%${index + 1}\$s", arg.toString()).replace("%${index + 1}\$d", arg.toString())
}

/**
 * Keeps the prepared timeline of one pane. Streaming replaces only the latest message and new entries are
 * appended; anything else (another session, new labels) rebuilds the timeline. Host-owned tail entries
 * (worktree cards) follow the history in its latest section, as current state rather than dated entries: each
 * update appends them to the cached history, reusing their prepared rows, so a changing card never rebuilds the
 * transcript and streaming before the cards stays incremental.
 * [update] is idempotent: repeating it with the same input returns the same timeline, so a discarded
 * composition that already advanced the cache cannot desynchronize it from the committed one.
 */
internal class TimelineCache {
    private var messages: List<MessageUi> = emptyList()
    private var labels: TimelineLabels? = null
    private var history: HbChatTimeline = HbChatTimeline.Empty
    private var tail: List<HbChatMessage> = emptyList()
    private var timeline: HbChatTimeline? = null

    fun update(
        next: List<MessageUi>,
        nextLabels: TimelineLabels,
        nextTail: List<HbChatMessage> = emptyList(),
    ): HbChatTimeline {
        val previous = history
        val isSameLabels = labels == nextLabels
        val sections = sectionsFor(next, nextLabels)
        val isSameSource = isSameLabels && next.size >= messages.size && messages.isNotEmpty()
        val keptPrefix = if (isSameSource) messages.size - 1 else 0
        val isIncremental = isSameSource &&
            next[keptPrefix].id == messages.last().id &&
            (0 until keptPrefix).all { next[it] == messages[it] }
        history = if (isIncremental) {
            val latest = next[keptPrefix]
            var updated = if (latest == messages.last()) history else history.replaceLatest(latest.toHb(nextLabels))
            for (index in messages.size until next.size) {
                updated = updated.append(
                    sections[index],
                    next[index].toHb(nextLabels),
                )
            }
            updated
        } else {
            next.foldIndexed(HbChatTimeline.Empty) { index, current, message ->
                current.append(sections[index], message.toHb(nextLabels))
            }
        }
        messages = next
        labels = nextLabels
        val composed = timeline?.takeIf { history === previous && isSameLabels && nextTail == tail }
            ?: history.appendTail(sections.lastOrNull() ?: tailSection(nextLabels), nextTail, previous = timeline)
        timeline = composed
        tail = nextTail
        return composed
    }

    /** Host entries of an empty transcript stay in the session group, or under no date heading. */
    private fun tailSection(labels: TimelineLabels): HbChatSection = if (labels.isGroupedByDate) {
        HbChatSection("tail", "", isDate = true)
    } else {
        HbChatSection("session", labels.section)
    }

    private fun sectionsFor(messages: List<MessageUi>, labels: TimelineLabels): List<HbChatSection> {
        if (!labels.isGroupedByDate) return messages.map { HbChatSection("session", labels.section) }
        var previousDate: kotlinx.datetime.LocalDate? = null
        var section = HbChatSection("empty", "")
        return messages.mapIndexed { index, message ->
            val date = labels.calendar.day(message.createdAt).takeIf { message.isTimestampKnown }
            if (index == 0 || date != previousDate) {
                section = HbChatSection(
                    "date:${message.id}",
                    date?.let {
                        labels.calendar.dateLabel(
                            message.createdAt,
                            labels.today,
                            labels.yesterday,
                        )
                    }.orEmpty(),
                    isDate = true,
                )
            }
            previousDate = date
            section
        }
    }
}

/**
 * The prepared timeline of [messages] followed by host-owned [tail] entries, updated incrementally while the
 * same session streams.
 */
@Composable
internal fun rememberStudioTimeline(
    sessionId: String,
    messages: ImmutableList<MessageUi>,
    labels: TimelineLabels,
    tail: ImmutableList<HbChatMessage> = persistentListOf(),
): HbChatTimeline {
    val cache = remember(sessionId) { TimelineCache() }
    return remember(cache, messages, labels, tail) { cache.update(messages, labels, tail) }
}

internal fun MessageUi.toHb(labels: TimelineLabels): HbChatMessage = when (this) {
    is MessageUi.Prompt -> HbChatMessage(
        id = id,
        author = labels.you,
        text = text,
        role = HbChatRole.User,
        appearance = labels.promptAppearance,
        label = labels.calendar.timeLabel(createdAt).takeIf { isTimestampKnown },
    )

    is MessageUi.Reply -> HbChatMessage(
        id = id,
        author = labels.agent,
        text = text,
        role = HbChatRole.Assistant,
        kind = HbMessageKind.Markdown,
        status = if (isStreaming) HbMessageStatus.Streaming else HbMessageStatus.Complete,
        toolCalls = tools.map { it.toHb(labels) }.toImmutableList(),
        appearance = labels.replyAppearance,
        parts = parts.mapIndexed { index, part ->
            part.toHb(labels, isStreaming = isStreaming && index == parts.lastIndex)
        }.toImmutableList(),
        label = labels.calendar.timeLabel(createdAt).takeIf { isTimestampKnown },
    )

    is MessageUi.Stopped -> HbChatMessage(
        id = id,
        author = labels.studio,
        text = fill(labels.stoppedTemplate, labels.durations.format(elapsed)),
        role = HbChatRole.System,
        kind = HbMessageKind.Notice,
    )

    is MessageUi.Failed -> HbChatMessage(
        id = id,
        author = labels.studio,
        text = labels.failed.of(kind),
        role = HbChatRole.System,
        kind = HbMessageKind.Notice,
        appearance = HbMessageAppearance(tone = HbTone.Danger),
    )
}

private fun ToolUi.toHb(labels: TimelineLabels): HbToolCall = HbToolCall(
    id = id,
    title = title,
    status = when (status) {
        ToolStatusUi.Pending -> HbToolStatus.Pending
        ToolStatusUi.Cancelled -> HbToolStatus.Cancelled
        ToolStatusUi.Running -> HbToolStatus.Running
        ToolStatusUi.Done -> HbToolStatus.Complete
        ToolStatusUi.Failed -> HbToolStatus.Error
    },
    summary = feedback?.let { labels.feedback?.summary(it) }.orEmpty(),
    blocks = listOfNotNull(
        feedbackBlock(labels.feedback),
        output.takeIf { it.isNotBlank() }?.let { HbToolBlock.Console("$id-console", it.trimEnd()) },
        diff?.let { HbToolBlock.Diff("$id-diff", it) },
    ).toImmutableList(),
)

private fun ToolUi.feedbackBlock(labels: FeedbackLabels?): HbToolBlock.Markdown? {
    val value = feedback ?: return null
    val copy = labels ?: return null
    return HbToolBlock.Markdown("$id-feedback", copy.markdown(value))
}

private fun ReplyPartUi.toHb(labels: TimelineLabels, isStreaming: Boolean): HbMessagePart = when (this) {
    is ReplyPartUi.Text -> HbMessagePart.Text(id, text)

    is ReplyPartUi.Tool -> HbMessagePart.Tool(tool.toHb(labels))

    is ReplyPartUi.Reasoning -> HbMessagePart.Tool(
        HbToolCall(
            id = "reasoning:$id",
            title = labels.reasoning,
            status = if (isStreaming) HbToolStatus.Running else HbToolStatus.Complete,
            summary = text.lineSequence().firstOrNull().orEmpty(),
            blocks = listOf(HbToolBlock.Markdown("reasoning-text:$id", text)).toImmutableList(),
            kind = HbToolKind.Reasoning,
        ),
    )
}
