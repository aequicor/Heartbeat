package io.aequicor.heartbeat.feature.autocontinue.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem

/**
 * The dialog the continuer reads, built from chat [items] (oldest first): every user message (marked
 * [DialogAuthor.Autopilot] when its id is in [autopilotItems]) and, for every agent turn between them, only the text of
 * its last assistant message — tool calls, tool results, reasoning, plans and notices are dropped. Empty texts are
 * skipped. Pure and deterministic.
 */
public fun dialogOf(items: List<SessionItem>, autopilotItems: Set<ItemId> = emptySet()): List<DialogEntry> {
    val entries = mutableListOf<DialogEntry>()
    var lastAgent: String? = null
    fun flushAgent() {
        lastAgent?.let { entries += DialogEntry(DialogAuthor.Agent, it) }
        lastAgent = null
    }
    for (item in items.sortedBy { it.info.position }.filterIsInstance<SessionItem.Message>()) {
        val text = item.parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }.trim()
        when {
            text.isEmpty() -> Unit

            item.role == MessageRole.User -> {
                flushAgent()
                val author = if (item.info.id in autopilotItems) DialogAuthor.Autopilot else DialogAuthor.Human
                entries += DialogEntry(author, text)
            }

            item.role == MessageRole.Assistant -> lastAgent = text
        }
    }
    flushAgent()
    return entries
}

/**
 * True when [next] nearly repeats [previous]: equal after normalizing case, punctuation and whitespace, or sharing at
 * least [REPEAT_SIMILARITY] of their distinct words (Jaccard). Used to stop an autopilot that keeps saying the same.
 */
public fun isNearRepeat(previous: String?, next: String): Boolean {
    if (previous == null) return false
    val a = words(previous)
    val b = words(next)
    if (a.isEmpty() || b.isEmpty()) return a == b
    if (a == b) return true
    val union = (a.toSet() + b.toSet()).size
    val common = a.toSet().intersect(b.toSet()).size
    return common.toDouble() / union >= REPEAT_SIMILARITY
}

/** Share of common distinct words above which two autopilot replies count as a repeat. */
public const val REPEAT_SIMILARITY: Double = 0.9

private fun words(text: String): List<String> {
    val spaced = text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
    return spaced.split(' ').filter { it.isNotEmpty() }
}
