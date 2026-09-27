package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem

/** Default size of the transcript carried into the target engine. */
internal const val HANDOFF_BUDGET_CHARS = 60_000

private const val TOOL_RESULT_LIMIT = 2_000

/**
 * Builds the first prompt of a target session from the source transcript. Resource references are local to
 * their engine and are replaced by placeholders; reasoning, system prompts, notices and tool arguments are
 * dropped. The newest entries win when the transcript exceeds [budgetChars].
 */
internal fun composeHandoff(
    transcript: Transcript,
    request: RequestId,
    budgetChars: Int = HANDOFF_BUDGET_CHARS,
): PromptRequest {
    val entries = transcript.items.mapNotNull { it.render() }
    val kept = ArrayDeque<String>()
    var used = 0
    var isShortened = false
    for (entry in entries.asReversed()) {
        if (used + entry.length > budgetChars) {
            isShortened = true
            // The newest entry alone may exceed the budget (a pasted log): keep its beginning rather than nothing.
            if (kept.isEmpty()) kept.addFirst(entry.take(budgetChars) + "…")
            break
        }
        kept.addFirst(entry)
        used += entry.length
    }
    val text = buildString {
        append(HEADER)
        if (isShortened || transcript.isTruncated) append("\n").append(OMITTED_NOTE)
        if (transcript.coverage != HistoryCoverage.Complete) append("\n").append(INCOMPLETE_NOTE)
        append("\n\n--- Transcript ---\n\n")
        if (kept.isEmpty()) append(EMPTY_NOTE) else append(kept.joinToString("\n\n"))
        append("\n\n--- End of transcript ---")
    }
    return PromptRequest(request, listOf(ContentPart.Text(text)))
}

/** Approximate text size of an item, used to bound history reads without rendering. */
internal fun SessionItem.approximateLength(): Int = render()?.length ?: 0

private fun SessionItem.render(): String? = when (this) {
    is SessionItem.Message -> when (role) {
        MessageRole.User -> parts.render()?.let { "User:\n$it" }
        MessageRole.Assistant -> parts.render()?.let { "Assistant:\n$it" }
        MessageRole.System -> null
    }

    is SessionItem.ToolCall -> "Tool call: $name (${status.name.lowercase()})"

    is SessionItem.ToolResult -> {
        val body = parts.render()?.let { if (it.length > TOOL_RESULT_LIMIT) it.take(TOOL_RESULT_LIMIT) + "…" else it }
        val title = if (failure == null) "Tool result:" else "Tool result (failed):"
        listOfNotNull(title, body).joinToString("\n")
    }

    is SessionItem.Plan -> steps.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "Plan:\n") {
        "- [${it.status.name.lowercase()}] ${it.text}"
    }

    is SessionItem.Notice, is SessionItem.UnsupportedItem -> null
}

private fun List<ContentPart>.render(): String? = mapNotNull { part ->
    when (part) {
        is ContentPart.Text -> part.text.takeIf { it.isNotBlank() }
        is ContentPart.Reasoning -> null
        is ContentPart.Image -> "[image omitted]"
        is ContentPart.Resource -> "[attachment omitted]"
    }
}.takeIf { it.isNotEmpty() }?.joinToString("\n")

private const val HEADER =
    "You are continuing a conversation that was started with another AI assistant. Its transcript follows, " +
        "oldest first. Treat it as context only: do not repeat tool calls or actions it describes. " +
        "Reply briefly to confirm that you have the context, then wait for the next request."
private const val OMITTED_NOTE = "Earlier parts of the conversation were omitted to keep the handoff short."
private const val INCOMPLETE_NOTE = "The stored history of the previous assistant may be incomplete."
private const val EMPTY_NOTE = "(The previous conversation has no messages.)"
