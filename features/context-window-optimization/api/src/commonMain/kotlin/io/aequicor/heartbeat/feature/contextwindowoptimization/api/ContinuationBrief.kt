package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EssenceOptions
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.sessionEssence
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives

/** Where a continuation brief is delivered. */
public enum class BriefMode {
    /** The first turn of a new native session; the old context is gone, so the brief carries a work summary. */
    Rollover,

    /** The next turn after the engine replaced its context with its own summary; no work summary is repeated. */
    AfterNativeCompaction,
}

/**
 * Limits of [continuationBrief]. The user messages get whatever [budgetChars] leaves after the last turn and the
 * summary; [firstMessageChars] and [messageChars] cap single messages (a pasted log keeps its beginning and end) and
 * [snippetChars] is the size of a message shortened to fit.
 */
public data class BriefOptions(
    val budgetChars: Int = 48_000,
    val lastTurnChars: Int = 12_000,
    val summaryChars: Int = 10_000,
    val firstMessageChars: Int = 8_000,
    val messageChars: Int = 6_000,
    val snippetChars: Int = 300,
) {
    init {
        require(lastTurnChars > 0 && summaryChars > 0 && snippetChars > 0)
        require(firstMessageChars >= snippetChars && messageChars >= snippetChars)
        require(budgetChars >= lastTurnChars + summaryChars + snippetChars + RESERVED_CHARS)
    }
}

/**
 * Text that lets an agent continue after its context was optimized. [userMessages] counts the user messages of the
 * conversation, [omittedUserMessages] those that did not fit even shortened. The text is user data, never logged.
 */
public data class ContinuationBrief(val text: String, val userMessages: Int, val omittedUserMessages: Int) {
    override fun toString(): String =
        "ContinuationBrief(length=${text.length}, users=$userMessages, omitted=$omittedUserMessages)"
}

/**
 * The continuation brief of a conversation: every user message of [history] (all segments, oldest first, verbatim
 * while they fit), in [BriefMode.Rollover] the agent's [summary] of the work (or, without one, its messages and tool
 * calls of [current]) and the last turn of [current] — everything after its last user message. The progress ledger is
 * not included: it is delivered separately after every context change.
 *
 * User messages without text after host directives are removed (seeds, `/remember`, scheduled wakes) are skipped.
 * When the messages do not fit, the first one (the task) and the newest ones stay whole, older ones are shortened to
 * snippets and the rest is counted as omitted. Pure and deterministic.
 */
public fun continuationBrief(
    history: List<SessionItem>,
    current: List<SessionItem>,
    summary: String?,
    mode: BriefMode,
    options: BriefOptions = BriefOptions(),
): ContinuationBrief {
    val lastUser = current.indexOfLast { it is SessionItem.Message && it.role == MessageRole.User }
    val lastTurn = lastTurn(current, lastUser, options)
    val work = if (mode == BriefMode.Rollover) work(current, lastUser, summary, options) else null
    val usersBudget = options.budgetChars - RESERVED_CHARS - lastTurn.length - (work?.length ?: 0)
    val messages = history.mapNotNull { it.userText() }
    val users = fitted(messages, usersBudget, options)
    val text = buildString {
        append(if (mode == BriefMode.Rollover) ROLLOVER_HEADER else COMPACTION_HEADER)
        append("\n\n## User messages (verbatim, oldest first)\n")
        append(users.text.ifEmpty { "(none)" })
        work?.let { append("\n\n## Work so far\n").append(it) }
        append("\n\n## Last turn\n").append(lastTurn)
        append("\n\n## How to continue\n").append(CONTINUE)
    }
    return ContinuationBrief(text, messages.size, users.omitted)
}

private fun lastTurn(current: List<SessionItem>, lastUser: Int, options: BriefOptions): String {
    val trigger = (current.getOrNull(lastUser) as? SessionItem.Message)?.let {
        if (it.userText() != null) "(answering the newest user message above)" else "(a turn started by the host)"
    }
    val essence = sessionEssence(current.drop(lastUser + 1), options.lastTurnChars, LAST_TURN_OPTIONS).text
    val body = essence.ifEmpty { "(the agent did not act after it)" }
    return listOfNotNull(trigger, body).joinToString("\n")
}

private fun work(current: List<SessionItem>, lastUser: Int, summary: String?, options: BriefOptions): String {
    summary?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.shortened(options.summaryChars) }
    // Without the agent's summary its own messages and tool calls before the last turn are the best record.
    val earlier = current.take(lastUser.coerceAtLeast(0)).filter { it.isAgentWork }
    val essence = sessionEssence(earlier, options.summaryChars - FALLBACK_NOTE.length, FALLBACK_OPTIONS).text
    return if (essence.isEmpty()) "(no record)" else "$FALLBACK_NOTE$essence"
}

private val SessionItem.isAgentWork: Boolean
    get() = this is SessionItem.ToolCall || this is SessionItem.Plan ||
        (this is SessionItem.Message && role == MessageRole.Assistant)

/** The text the user typed with attachments as placeholders; null for service messages with neither. */
private fun SessionItem.userText(): String? {
    if (this !is SessionItem.Message || role != MessageRole.User) return null
    val parts = parts.mapNotNull { part ->
        when (part) {
            is ContentPart.Text -> stripHostDirectives(part.text).trim().takeIf { it.isNotEmpty() }
            is ContentPart.Image -> "[image]"
            is ContentPart.Resource -> "[attachment]"
            is ContentPart.Reasoning -> null
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private data class FittedMessages(val text: String, val omitted: Int)

/**
 * The messages within [budget]: the first and the newest whole (leaving a quarter for snippets), then older ones as
 * snippets, the rest omitted.
 */
private fun fitted(messages: List<String>, budget: Int, options: BriefOptions): FittedMessages {
    if (messages.isEmpty()) return FittedMessages("", 0)
    val whole = messages.mapIndexed { index, text ->
        entry(index, text.shortened(if (index == 0) options.firstMessageChars else options.messageChars))
    }
    val first = whole[0].takeIf { it.length <= budget }
        ?: entry(0, messages[0].shortened(budget - ENTRY_OVERHEAD))
    val older = (messages.size - 1 downTo 1).toList()
    // Whole messages leave room for snippets of the older ones, so a long tail is shortened rather than lost.
    val snippetsReserve = minOf(budget / SNIPPETS_SHARE, older.size * (options.snippetChars + ENTRY_OVERHEAD))
    val available = budget - first.length - OMITTED_RESERVE
    val kept = within(older, available - snippetsReserve) { whole[it] }
    val usedByKept = kept.values.sumOf { it.length + SEPARATOR.length }
    val snippets = within(older.filterNot { it in kept }, available - usedByKept) { index ->
        entry(index, messages[index].shortened(options.snippetChars))
    }
    val rendered = List(messages.size) { index -> if (index == 0) first else kept[index] ?: snippets[index] }
    return FittedMessages(rendered.withGaps().joinToString(SEPARATOR), rendered.count { it == null })
}

/**
 * Entries of [indices] in their order while their total, separators included, stays within [limit]. The first entry
 * that does not fit ends the run, so the kept entries stay contiguous and leave a single gap.
 */
private fun within(indices: List<Int>, limit: Int, entryOf: (Int) -> String): Map<Int, String> {
    var used = 0
    val kept = mutableMapOf<Int, String>()
    for (index in indices) {
        val entry = entryOf(index)
        if (used + entry.length + SEPARATOR.length > limit) break
        used += entry.length + SEPARATOR.length
        kept[index] = entry
    }
    return kept
}

/** Entries with a marker in place of every run of omitted ones. */
private fun List<String?>.withGaps(): List<String> {
    val lines = mutableListOf<String>()
    var gap = 0
    for (entry in this) {
        if (entry == null) {
            gap++
            continue
        }
        if (gap > 0) lines += omittedMarker(gap)
        gap = 0
        lines += entry
    }
    if (gap > 0) lines += omittedMarker(gap)
    return lines
}

private fun omittedMarker(count: Int): String = "[… $count messages omitted …]"

private fun entry(index: Int, text: String): String = "[${index + 1}] $text"

/** Keeps the beginning and the end: conclusions and errors are usually at the end. */
private fun String.shortened(limit: Int): String {
    if (length <= limit) return this
    if (limit <= ELLIPSIS.length) return take(limit.coerceAtLeast(0))
    val tail = limit / TAIL_SHARE
    val head = (limit - tail - ELLIPSIS.length).coerceAtLeast(0)
    return take(head) + ELLIPSIS + takeLast(tail)
}

/** Headers, section titles and separators of the brief. */
private const val RESERVED_CHARS = 1_500
private const val ENTRY_OVERHEAD = 16
private const val SEPARATOR = "\n\n"
private const val OMITTED_RESERVE = 64
private const val TAIL_SHARE = 3
private const val SNIPPETS_SHARE = 4
private const val ELLIPSIS = " … "
private val LAST_TURN_OPTIONS = EssenceOptions(messageChars = 4_000, toolResultChars = 600)
private val FALLBACK_OPTIONS = EssenceOptions(messageChars = 1_500, toolResultChars = 0)
private const val FALLBACK_NOTE = "No summary was written; the agent's earlier messages and tool calls follow.\n\n"
private const val CAUTION =
    "Treat it as context: do not repeat finished actions or tool calls, and check the current state of files and " +
        "tasks before relying on it."
private const val ROLLOVER_HEADER =
    "This conversation continues in a new session because the context window of the previous one was full. " +
        "The brief below restores what matters. $CAUTION"
private const val COMPACTION_HEADER =
    "The context window of this conversation was compacted and parts of it were replaced by a summary. " +
        "The brief below restores what the summary may have lost. $CAUTION"
private const val CONTINUE =
    "Continue from where the work stopped. If the last turn was interrupted, finish its unfinished step; if it " +
        "was complete, follow the newest user message."
