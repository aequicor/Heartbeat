package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.cut
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostedToolName
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives

/**
 * The newest part of a transcript within [budget] characters, oldest first: messages, tool calls and their results,
 * each shortened. Host directives are removed from user messages; reasoning and plans are left out.
 */
internal fun renderTail(items: List<SessionItem>, budget: Int): String {
    val lines = items.asReversed().mapNotNull { it.render() }
    // Characters used once each line and its separator are kept, newest first.
    val used = lines.runningFold(0) { total, line -> total + line.length + 1 }.drop(1)
    val kept = lines.asSequence().zip(used.asSequence())
        .takeWhile { (_, total) -> total - 1 <= budget }
        .map { it.first }
        .toList()
    return kept.asReversed().joinToString("\n").ifEmpty { "(no readable transcript)" }
}

private fun SessionItem.render(): String? = when (this) {
    is SessionItem.Message -> when (role) {
        MessageRole.User -> "User: " + cut(stripHostDirectives(text()).trim(), MESSAGE_CHARS)
        MessageRole.Assistant -> "Assistant: " + cut(text().trim(), MESSAGE_CHARS)
        MessageRole.System -> null
    }

    is SessionItem.ToolCall -> "Tool call ${hostedToolName(name)} [$status]: " + cut(arguments, TOOL_CHARS)

    is SessionItem.ToolResult -> (if (failure == null) "Tool result: " else "Tool failure: ") +
        cut(parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }, TOOL_CHARS)

    is SessionItem.Notice -> "Notice: " + cut(text, TOOL_CHARS)

    is SessionItem.Plan, is SessionItem.UnsupportedItem -> null
}

private fun SessionItem.Message.text(): String =
    parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }

private const val MESSAGE_CHARS = 2_000
private const val TOOL_CHARS = 400
