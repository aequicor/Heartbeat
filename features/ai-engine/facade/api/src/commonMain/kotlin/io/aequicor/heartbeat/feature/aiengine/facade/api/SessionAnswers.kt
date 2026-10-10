package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * The final answer of [turn]: the last assistant message with text among the messages of that turn. History marks
 * items with the engine's own turn ids, which differ from the ids a handle gives its submissions, so a turn found
 * nowhere by id takes the messages after the last user message (its prompt) instead, unless [isMarkedOnly]. Null when
 * there is none. Cut to [maxChars] characters.
 */
public fun answerOf(items: List<SessionItem>, turn: TurnId, maxChars: Int, isMarkedOnly: Boolean = false): String? {
    val messages = items.filterIsInstance<SessionItem.Message>()
    val marked = messages.filter { it.info.turn == turn }
    val afterPrompt = if (isMarkedOnly) emptyList() else messages.takeLastWhile { it.role != MessageRole.User }
    val own = marked.ifEmpty { afterPrompt }
    val answer = own.lastOrNull { it.role == MessageRole.Assistant && it.text().isNotBlank() }
    return answer?.text()?.let { cut(it.trim(), maxChars) }
}

private fun SessionItem.Message.text(): String =
    parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
