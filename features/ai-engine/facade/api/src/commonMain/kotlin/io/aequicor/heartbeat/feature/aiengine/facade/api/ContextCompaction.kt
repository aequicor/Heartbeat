package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/**
 * Marker: the session compacts its native context on request. A compaction is a turn: it is submitted through
 * [SendsPrompts] as a [PromptRequest] with [PromptRequest.compaction] and no parts, follows the same acceptance,
 * cancellation and recovery as a prompt and ends with a [SessionItem.Notice] of [NoticeKind.ContextCompacted].
 * Prompt hooks never augment it. The facade refuses a compaction request before acceptance when the session does
 * not resolve this feature, so adapters without it never see one.
 */
public interface CompactsContext : EngineFeature {
    /** Whether [ContextCompaction.instructions] steer the summary; otherwise they are refused before acceptance. */
    public val isInstructionsSupported: Boolean

    /** Typed compaction key. */
    public companion object : EngineFeatureKey<CompactsContext>(
        EngineFeatureId("session.compact"),
        CompactsContext::class,
    )
}

/** A request to replace the retained native context with its summary. [instructions] are user data, never logged. */
@Serializable
public data class ContextCompaction(val instructions: String? = null) {
    init {
        require(instructions == null || instructions.isNotBlank())
    }

    override fun toString(): String = "ContextCompaction(instructions=${instructions?.length ?: 0})"
}

/** Kind of a host-recognized service notice; null for free-form engine notices. */
@Serializable
public enum class NoticeKind {
    /** The native context was replaced by its summary; the transcript before the notice stays visible. */
    ContextCompacted,

    /** The conversation continues in a new native session seeded with the essence of the previous one. */
    ContextRollover,
}

/** Rendering limits of [sessionEssence]. */
public data class EssenceOptions(
    /** Longest kept message text; longer texts keep their beginning and end. */
    val messageChars: Int = 4_000,
    /** Longest kept tool result; failures are kept with the same limit. */
    val toolResultChars: Int = 600,
    /** Keeps a one-line record of each tool call (name and status, never arguments). */
    val isToolCallsKept: Boolean = true,
) {
    init {
        require(messageChars > 0 && toolResultChars >= 0)
    }
}

/** Plain-text essence of a transcript. [omittedItems] counts renderable items that did not fit the budget. */
public data class SessionEssence(val text: String, val keptItems: Int, val omittedItems: Int) {
    override fun toString(): String = "SessionEssence(length=${text.length}, kept=$keptItems, omitted=$omittedItems)"
}

/**
 * The essence of [items], oldest first, within [budgetChars]: user and assistant text, a line per tool call and
 * shortened tool results and plans. Reasoning, tool arguments, system messages and unsupported items are dropped,
 * host directives are stripped from user text and resources become placeholders. The newest entries win; when even
 * the newest entry does not fit, its beginning is kept. Pure and deterministic: equal input gives equal text.
 */
public fun sessionEssence(
    items: List<SessionItem>,
    budgetChars: Int,
    options: EssenceOptions = EssenceOptions(),
): SessionEssence {
    require(budgetChars > 0)
    val entries = items.mapNotNull { it.essence(options) }
    val kept = ArrayDeque<String>()
    var used = 0
    for (entry in entries.asReversed()) {
        val cost = entry.length + ENTRY_SEPARATOR.length
        if (used + cost > budgetChars) {
            if (kept.isEmpty()) kept.addFirst(entry.take(budgetChars - ELLIPSIS.length) + ELLIPSIS)
            break
        }
        kept.addFirst(entry)
        used += cost
    }
    return SessionEssence(kept.joinToString(ENTRY_SEPARATOR), kept.size, entries.size - kept.size)
}

private fun SessionItem.essence(options: EssenceOptions): String? = when (this) {
    is SessionItem.Message -> messageEssence(options)

    is SessionItem.ToolCall -> if (options.isToolCallsKept) "Tool call: $name (${status.name.lowercase()})" else null

    is SessionItem.ToolResult -> {
        val title = if (failure == null) "Tool result" else "Tool result (failed)"
        val body = parts.essence()?.takeIf { options.toolResultChars > 0 }?.shortened(options.toolResultChars)
        if (body == null) "$title: ${parts.essenceLength()} chars" else "$title: $body"
    }

    is SessionItem.Plan -> steps.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "Plan:\n") {
        "- [${it.status.name.lowercase()}] ${it.text}"
    }

    is SessionItem.Notice -> if (kind == null) null else "[${kind.name}]"

    is SessionItem.UnsupportedItem -> null
}

private fun SessionItem.Message.messageEssence(options: EssenceOptions): String? {
    val author = when (role) {
        MessageRole.User -> "User"
        MessageRole.Assistant -> "Assistant"
        MessageRole.System -> return null
    }
    return parts.essence()?.let { "$author: ${it.shortened(options.messageChars)}" }
}

private fun List<ContentPart>.essence(): String? = mapNotNull { part ->
    when (part) {
        is ContentPart.Text -> stripHostDirectives(part.text).trim().takeIf { it.isNotEmpty() }
        is ContentPart.Reasoning -> null
        is ContentPart.Image -> "[image]"
        is ContentPart.Resource -> "[attachment]"
    }
}.takeIf { it.isNotEmpty() }?.joinToString("\n")

private fun List<ContentPart>.essenceLength(): Int = sumOf { (it as? ContentPart.Text)?.text?.length ?: 0 }

/** Keeps the beginning and the end: errors and conclusions are usually at the end of long output. */
private fun String.shortened(limit: Int): String {
    if (length <= limit) return this
    val tail = limit / TAIL_SHARE
    val head = (limit - tail - ELLIPSIS.length).coerceAtLeast(0)
    return take(head) + ELLIPSIS + takeLast(tail)
}

private const val ENTRY_SEPARATOR = "\n\n"
private const val ELLIPSIS = " … "
private const val TAIL_SHARE = 3
