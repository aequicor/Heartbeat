package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives

/**
 * Prefix of the request id of a handoff summary turn: before a rollover the old session writes the summary of its work
 * for the new one. Tools are refused during such a turn.
 */
public const val HANDOFF_REQUEST_PREFIX: String = "handoff-"

/** Marks the host directive of a handoff summary turn in the transcript. */
private const val HANDOFF_MARKER = "[heartbeat:handoff-summary]"

/** The host directive (the whole prompt) of a handoff summary turn. */
public const val HANDOFF_SUMMARY_PROMPT: String =
    "$HANDOFF_MARKER The context window of this session is nearly full. The conversation will continue in a new " +
        "session that will not see this one; it receives the user's messages verbatim and your last turn, but not " +
        "your reasoning. Do not call any tools. Write a handoff summary for it in Markdown, at most about 1500 " +
        "words, without repeating the user's messages:\n" +
        "1. Decisions made and why, including rejected options.\n" +
        "2. What is done: files changed, commands run, commits, results of checks.\n" +
        "3. Open problems and failed attempts that should not be repeated.\n" +
        "4. The exact current step and what to do next."

/** A transcript without its newest handoff summary turn ([items]) and the agent's answer to it ([summary]). */
public data class HandoffSplit(val items: List<SessionItem>, val summary: String?) {
    override fun toString(): String = "HandoffSplit(items=${items.size}, summary=${summary?.length})"
}

/**
 * Splits [items] at the newest handoff summary turn: the items before it and the text of the agent's last message after
 * it. Without such a turn, or without an answer to it, [HandoffSplit.summary] is null. Only a message that carries
 * nothing but host directives counts as the turn, so text the user typed never does.
 */
public fun handoffSplit(items: List<SessionItem>): HandoffSplit {
    val index = items.indexOfLast { it.isHandoffRequest }
    if (index < 0) return HandoffSplit(items, null)
    val summary = items.drop(index + 1)
        .filterIsInstance<SessionItem.Message>()
        .filter { it.role == MessageRole.Assistant }
        .mapNotNull { message -> message.parts.texts().takeIf { it.isNotEmpty() } }
        .lastOrNull()
    return HandoffSplit(items.take(index), summary)
}

private val SessionItem.isHandoffRequest: Boolean
    get() = this is SessionItem.Message && role == MessageRole.User && parts.any { part ->
        part is ContentPart.Text && HANDOFF_MARKER in part.text && stripHostDirectives(part.text).isBlank()
    }

private fun List<ContentPart>.texts(): String =
    filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text.trim() }.trim()
