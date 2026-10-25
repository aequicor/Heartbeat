package io.aequicor.heartbeat.feature.aistudio.screenui

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
import io.aequicor.heartbeat.feature.aistudio.screenmodel.FailureUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
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
    val learning: LearningLabels? = null,
    val compacted: String,
    val rolledOver: String,
    val collapsedTools: String,
    val organismResult: String = "",
    val engineChanged: String = "",
    val transferTruncated: String = "",
)

/** Failed-run notices, one per [FailureUi]. */
@Immutable
internal data class FailureLabels(
    val unknown: String,
    val limit: String = unknown,
    val context: String = unknown,
    val authentication: String = unknown,
    val network: String = unknown,
    val serviceUnavailable: String = network,
    val quota: String = unknown,
    val credentialsMissing: String = unknown,
    val connectionChanged: String = unknown,
    val access: String = unknown,
    val requestRejected: String = unknown,
    val engineUnavailable: String = unknown,
) {
    fun of(kind: FailureUi): String = when (kind) {
        FailureUi.Limit -> limit
        FailureUi.Quota -> quota
        FailureUi.Context -> context
        FailureUi.Authentication -> authentication
        FailureUi.CredentialsMissing -> credentialsMissing
        FailureUi.ConnectionChanged -> connectionChanged
        FailureUi.Network -> network
        FailureUi.ServiceUnavailable -> serviceUnavailable
        FailureUi.Access -> access
        FailureUi.RequestRejected -> requestRejected
        FailureUi.EngineUnavailable -> engineUnavailable
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
 * appended; anything else (another session, new labels) rebuilds the timeline. Host-owned worktree cards join
 * the transcript at the moment they are invoked: a card lands after the messages already present and stays
 * there while later turns stream below it. Every update refreshes offered card content in place; cards kept
 * by the caller as retained survive after they leave the offered window, the rest are retired once absent.
 * [update] is idempotent: repeating it with the same input returns the same timeline, so a discarded
 * composition that already advanced the cache cannot desynchronize it from the committed one.
 */
internal class TimelineCache(private val placement: TimelineWeaves = TimelineWeaves()) {
    private var messages: List<MessageUi> = emptyList()
    private var labels: TimelineLabels? = null
    private var history: HbChatTimeline = HbChatTimeline.Empty
    private var cards: List<HbChatMessage> = emptyList()
    private var retained: Set<String> = emptySet()
    private var observedCards = placement.cards
    private var observedPositions = placement.positions
    private val outputs = RunBuildOutputs()

    /** Build cards shown inside a reply, right after the run_build call that started them, by that call's id. */
    private var anchored: Map<String, HbChatMessage> = emptyMap()

    /** Cards already woven, by id; their insertion points replay full rebuilds in weave order. */
    private var woven: Map<String, HbChatMessage>
        get() = placement.cards
        set(value) {
            placement.cards = value
        }
    private var weaves: List<TimelineWeave>
        get() = placement.positions
        set(value) {
            placement.positions = value
        }

    fun update(
        next: List<MessageUi>,
        nextLabels: TimelineLabels,
        nextCards: List<HbChatMessage> = emptyList(),
        nextRetained: Set<String> = emptySet(),
    ): HbChatTimeline {
        // A replacement pane may have advanced placement before its composition was discarded.
        val isPlacementChanged = observedCards !== woven || observedPositions !== weaves
        if (!isPlacementChanged && next == messages && nextCards == cards) {
            if (nextLabels == labels && nextRetained == retained) return history
        }
        val sections = sectionsFor(next, nextLabels)
        val previousAnchored = anchored
        val positions = weaves.associateTo(HashMap()) { it.id to it.afterIndex }
        anchored = anchoredCards(
            next,
            nextCards,
            nextCards.associate { it.id to (positions[it.id] ?: next.lastIndex) },
            outputs,
        )
        history = if (isPlacementChanged) {
            rebuild(next, sections, nextLabels)
        } else {
            syncMessages(next, nextLabels, sections)
        }
        history = refreshAnchored(next, nextLabels, previousAnchored)
        messages = next
        labels = nextLabels
        // The unfiltered inputs are kept so that an unchanged update short-circuits before anchoring again.
        cards = nextCards
        retained = nextRetained
        val inline = anchored.values.mapTo(HashSet()) { it.id }
        placement.inline = inline
        val updated = weave(next, nextCards, nextRetained, inline, sections, nextLabels)
        observedCards = woven
        observedPositions = weaves
        return updated
    }

    /** Replies whose inline build cards changed are converted again; the rest of the timeline stays as it is. */
    private fun refreshAnchored(
        next: List<MessageUi>,
        nextLabels: TimelineLabels,
        previous: Map<String, HbChatMessage>,
    ): HbChatTimeline {
        if (previous == anchored) return history
        val changed = (previous.keys + anchored.keys).filterTo(HashSet()) { previous[it] != anchored[it] }
        var updated = history
        next.filterIsInstance<MessageUi.Reply>()
            .filter { reply -> reply.parts.any { it is ReplyPartUi.Tool && it.tool.id in changed } }
            .forEach { updated = updated.replace(it.id, it.toHb(nextLabels, anchored)) }
        return updated
    }

    /** Streams into the latest message and appends the arrived ones; another source rebuilds the timeline. */
    private fun syncMessages(
        next: List<MessageUi>,
        nextLabels: TimelineLabels,
        sections: List<HbChatSection>,
    ): HbChatTimeline {
        val isSameLabels = labels == nextLabels
        val isSameSource = isSameLabels && next.size >= messages.size && messages.isNotEmpty()
        val keptPrefix = if (isSameSource) messages.size - 1 else 0
        val isIncremental = isSameSource &&
            next[keptPrefix].id == messages.last().id &&
            (0 until keptPrefix).all { next[it] == messages[it] }
        if (!isIncremental) return rebuild(next, sections, nextLabels)
        val latest = next[keptPrefix]
        var updated = if (latest == messages.last()) {
            history
        } else {
            history.replace(latest.id, latest.toHb(nextLabels, anchored))
        }
        for (index in messages.size until next.size) {
            updated = updated.append(sections[index], next[index].toHb(nextLabels, anchored))
        }
        return updated
    }

    /**
     * Appends new cards at the current end, refreshes known ones in place and retires absent ones. A card shown
     * [inline] in a reply still records the point it appeared at: once it leaves the reply it returns there.
     */
    private fun weave(
        next: List<MessageUi>,
        nextCards: List<HbChatMessage>,
        nextRetained: Set<String>,
        inline: Set<String>,
        sections: List<HbChatSection>,
        nextLabels: TimelineLabels,
    ): HbChatTimeline {
        val offered = nextCards.mapTo(HashSet()) { it.id }
        offered.addAll(nextRetained)
        val positioned = weaves.mapTo(HashSet()) { it.id }
        var updated = history
        var isReturned = false
        for (card in nextCards) {
            val isPositioned = card.id in positioned
            if (!isPositioned) weaves = weaves + TimelineWeave(card.id, next.lastIndex)
            val shown = woven[card.id]
            when {
                card.id in inline -> if (shown != null) {
                    woven = woven - card.id
                    updated = updated.remove(card.id)
                }

                shown == null -> {
                    woven = woven + (card.id to card)
                    if (isPositioned) {
                        isReturned = true
                    } else {
                        updated = updated.append(cardSection(sections, nextLabels), card)
                    }
                }

                shown != card -> {
                    woven = woven + (card.id to card)
                    updated = updated.replace(card.id, card)
                }
            }
        }
        updated = retire(updated, offered)
        if (isReturned) updated = rebuild(next, sections, nextLabels)
        history = updated
        return updated
    }

    /** Drops the cards and invocation points that are neither offered nor retained. */
    private fun retire(timeline: HbChatTimeline, offered: Set<String>): HbChatTimeline {
        var updated = timeline
        for (id in weaves.map { it.id }.filterNot(offered::contains)) {
            if (id in woven) updated = updated.remove(id)
            woven = woven - id
            weaves = weaves.filterNot { it.id == id }
        }
        return updated
    }

    /** The cards join the section of the message they follow, or the host section of an empty transcript. */
    private fun cardSection(sections: List<HbChatSection>, labels: TimelineLabels): HbChatSection =
        sections.lastOrNull() ?: tailSection(labels)

    /** Replays the woven cards at their recorded points; cards before the first message lead the transcript. */
    private fun rebuild(
        next: List<MessageUi>,
        sections: List<HbChatSection>,
        nextLabels: TimelineLabels,
    ): HbChatTimeline {
        var rebuilt = HbChatTimeline.Empty
        val leading = weaves.filter { it.afterIndex < 0 }
        if (leading.isNotEmpty()) {
            val section = cardSection(emptyList(), nextLabels)
            for (weave in leading) woven[weave.id]?.let { rebuilt = rebuilt.append(section, it) }
        }
        rebuilt = next.foldIndexed(rebuilt) { index, current, message ->
            var appended = current.append(sections[index], message.toHb(nextLabels, anchored))
            for (weave in weaves) {
                if (weave.afterIndex == index) woven[weave.id]?.let { appended = appended.append(sections[index], it) }
            }
            appended
        }
        val trailing = weaves.filter { it.afterIndex in next.size..Int.MAX_VALUE }
        if (trailing.isNotEmpty()) {
            val section = cardSection(sections, nextLabels)
            for (weave in trailing) woven[weave.id]?.let { rebuilt = rebuilt.append(section, it) }
        }
        return rebuilt
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
 * The prepared timeline of [messages] with host-owned [cards] woven at their invocation points, updated
 * incrementally while the same session streams.
 */
@Composable
internal fun rememberStudioTimeline(
    sessionId: String,
    state: StudioTimelineState,
    messages: ImmutableList<MessageUi>,
    labels: TimelineLabels,
    cards: ImmutableList<HbChatMessage> = persistentListOf(),
    retained: ImmutableSet<String> = persistentSetOf(),
): HbChatTimeline {
    val cache = remember(sessionId, state) { TimelineCache(state.session(sessionId)) }
    return cache.update(messages, labels, cards, retained)
}

/**
 * Build cards that belong inside a reply: a `build:<operation>` card goes right after the run_build call whose
 * result names that operation. A call that names no operation — still waiting for its build, or cut off by the
 * engine's tool timeout — takes the newest card of the command in its arguments; any card left goes after the
 * remaining waiting calls, newest to newest. Without an operation a call takes only a card that appeared after
 * its reply started: [appearedAfter] maps a card id to the index of the last message present when the card first
 * showed (a card missing from it appears now), so an older build never moves into a newer call. A card that
 * showed before any message has no known start and may go to any call. Keys are the calls' ids; a card without
 * such a visible call stays in the timeline. [outputs] keeps parsed call outputs between updates.
 */
internal fun anchoredCards(
    messages: List<MessageUi>,
    cards: List<HbChatMessage>,
    appearedAfter: Map<String, Int> = emptyMap(),
    outputs: RunBuildOutputs = RunBuildOutputs(),
): Map<String, HbChatMessage> {
    val builds = cards.filter { it.id.startsWith(BUILD_CARD_PREFIX) }
    if (builds.isEmpty()) return emptyMap()
    val calls = messages.withIndex().flatMap { (index, message) ->
        val reply = message as? MessageUi.Reply ?: return@flatMap emptyList()
        reply.parts.filterIsInstance<ReplyPartUi.Tool>()
            .filter { it.tool.title == RUN_BUILD_TOOL }
            .map { RunBuildCall(it.tool, index, outputs.of(it.tool)) }
    }
    outputs.retainOnly(calls.mapTo(HashSet()) { it.tool.id })
    fun RunBuildCall.mayTake(card: HbChatMessage): Boolean {
        val appeared = appearedAfter[card.id] ?: messages.lastIndex
        return appeared < 0 || appeared >= replyIndex
    }
    val anchored = LinkedHashMap<String, HbChatMessage>()
    builds.forEach { card ->
        val operation = card.id.removePrefix(BUILD_CARD_PREFIX)
        calls.firstOrNull { operation in it.operations }?.let { anchored[it.tool.id] = card }
    }
    val unnamedCalls = calls.filter { it.tool.id !in anchored && it.operations.isEmpty() }
    val taken = anchored.values.mapTo(HashSet()) { it.id }
    val unnamed = builds.filterNot { it.id in taken }.asReversed().filterNot { card ->
        val command = (card.parts.firstOrNull() as? HbMessagePart.Tool)?.call?.title ?: return@filterNot false
        unnamedCalls.lastOrNull { it.tool.id !in anchored && command in it.commands && it.mayTake(card) }
            ?.also { anchored[it.tool.id] = card } != null
    }
    val waiting = unnamedCalls.filter { it.tool.id !in anchored && it.tool.status in waitingStatuses }
    pairWaiting(waiting.asReversed(), unnamed, anchored) { call, card -> call.mayTake(card) }
    return anchored
}

/** Newest waiting call first takes the newest remaining card it may take. */
private inline fun pairWaiting(
    newestFirst: List<RunBuildCall>,
    cards: List<HbChatMessage>,
    anchored: MutableMap<String, HbChatMessage>,
    mayTake: (RunBuildCall, HbChatMessage) -> Boolean,
) {
    val left = cards.toMutableList()
    for (call in newestFirst) {
        val card = left.firstOrNull { mayTake(call, it) } ?: continue
        left -= card
        anchored[call.tool.id] = card
    }
}

/** A run_build call of the transcript with the operations and commands its output names. */
private class RunBuildCall(val tool: ToolUi, val replyIndex: Int, output: RunBuildOutput) {
    val operations: Set<String> = output.operations
    val commands: Set<String> = output.commands
}

/** The operations and commands named by one run_build [output]. */
internal class RunBuildOutput(val output: String) {
    val operations: Set<String> = OPERATION_ID.findAll(output).mapTo(HashSet()) { it.groupValues[1] }
    val commands: Set<String> = BUILD_COMMAND.findAll(output).mapTo(HashSet()) { it.groupValues[1] }
}

/**
 * Parsed run_build outputs by call id. A streamed delta changes one message, so the other calls reuse their
 * parse instead of matching every output again; calls that left the transcript are dropped.
 */
internal class RunBuildOutputs {
    private val parsed = HashMap<String, RunBuildOutput>()

    fun of(tool: ToolUi): RunBuildOutput {
        val known = parsed[tool.id]
        if (known != null && known.output == tool.output) return known
        return RunBuildOutput(tool.output).also { parsed[tool.id] = it }
    }

    fun retainOnly(ids: Set<String>) {
        parsed.keys.retainAll(ids)
    }
}

private const val BUILD_CARD_PREFIX = "build:"
private const val RUN_BUILD_TOOL = "run_build"
private val OPERATION_ID = Regex("\"id\"\\s*:\\s*\"([^\"\\\\]+)\"")
private val BUILD_COMMAND = Regex("\"command\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val waitingStatuses = setOf(ToolStatusUi.Pending, ToolStatusUi.Running)

internal fun MessageUi.toHb(labels: TimelineLabels, anchored: Map<String, HbChatMessage> = emptyMap()): HbChatMessage =
    when (this) {
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
            hasEmbeddedContent = checklistIds.isNotEmpty() || cardIds.isNotEmpty() || tools.any { it.plan != null },
            author = labels.agent,
            text = text,
            role = HbChatRole.Assistant,
            kind = HbMessageKind.Markdown,
            status = if (isStreaming) HbMessageStatus.Streaming else HbMessageStatus.Complete,
            toolCalls = tools.map { it.toHb(labels) }.toImmutableList(),
            appearance = labels.replyAppearance,
            parts = parts.flatMapIndexed { index, part ->
                val own = part.toHb(labels, isStreaming = isStreaming && index == streamingPartIndex)
                val card = (part as? ReplyPartUi.Tool)?.let { anchored[it.tool.id] }
                listOf(own) + card?.parts.orEmpty()
            }.toImmutableList(),
            label = labels.calendar.timeLabel(createdAt).takeIf { isTimestampKnown },
        )

        is MessageUi.OrganismResult -> HbChatMessage(
            id = id,
            author = labels.studio,
            text = fill(labels.organismResult, branch, base, commit),
            role = HbChatRole.System,
            kind = HbMessageKind.Markdown,
        )

        is MessageUi.Stopped -> HbChatMessage(
            id = id,
            author = labels.studio,
            text = fill(labels.stoppedTemplate, labels.durations.format(elapsed)),
            role = HbChatRole.System,
            kind = HbMessageKind.Notice,
        )

        is MessageUi.ContextNotice -> HbChatMessage(
            id = id,
            author = labels.studio,
            text = noticeText(labels),
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

/**
 * The part showing the reply's progress: the last one, unless the collapsed line already runs for hidden work that
 * follows it; then a visible reasoning before the hidden calls does not pose as the latest activity.
 */
private val MessageUi.Reply.streamingPartIndex: Int
    get() = if (parts.any { it is ReplyPartUi.CollapsedTools && it.isRunning }) -1 else parts.lastIndex

private fun ToolUi.toHb(labels: TimelineLabels): HbToolCall = HbToolCall(
    id = id,
    title = learning?.let { labels.learning?.title(it) } ?: title,
    status = when (status) {
        ToolStatusUi.Pending -> HbToolStatus.Pending
        ToolStatusUi.Cancelled -> HbToolStatus.Cancelled
        ToolStatusUi.Running -> HbToolStatus.Running
        ToolStatusUi.Done -> HbToolStatus.Complete
        ToolStatusUi.Failed -> HbToolStatus.Error
    },
    summary = feedback?.let { labels.feedback?.summary(it) }
        ?: learning?.let { labels.learning?.summary(it) }.orEmpty(),
    blocks = listOfNotNull(
        feedbackBlock(labels.feedback),
        // Literal, not Markdown: link definitions and URLs must stay visible in what becomes a future prompt.
        learning?.content?.takeIf { it.isNotBlank() }?.let { HbToolBlock.Console("$id-learning", it) },
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

    is ReplyPartUi.CollapsedTools -> HbMessagePart.Tool(
        HbToolCall(
            id = id,
            title = fill(labels.collapsedTools, count),
            status = if (isRunning) HbToolStatus.Running else HbToolStatus.Complete,
        ),
    )

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

private fun MessageUi.ContextNotice.noticeText(labels: TimelineLabels): String = when {
    isTransferTruncated -> labels.transferTruncated
    engineFrom != null && engineTo != null -> fill(labels.engineChanged, engineFrom.orEmpty(), engineTo.orEmpty())
    isRollover -> labels.rolledOver
    else -> labels.compacted
}
