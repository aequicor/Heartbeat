package io.aequicor.heartbeat.feature.checklist.screenmodel

import androidx.compose.runtime.Immutable

/** Own edits remembered per list; older ones are dropped first, so a silently rejected edit cannot pin the draft. */
private const val MAX_REMEMBERED_EDITS = 256

/**
 * Local editing state of one text field whose owner (the profile machine and its durable journal, possibly behind a
 * remote panel) publishes drafts asynchronously.
 *
 * [text] is what the editor shows. Every user edit becomes [text] at once and stays [pending] until the owner
 * publishes it, so late answers never overwrite newer typing. Acknowledged edits and the values they replaced move
 * to [settled]; a later echo of one of them is a stale state and is ignored. Any other published value replaces
 * [text] only when no edit is in flight, so the owner's own change wins once the user's input has been answered.
 */
@Immutable
data class ChecklistTextDraft(
    val text: String,
    val published: String = text,
    val pending: List<String> = emptyList(),
    val settled: List<String> = emptyList(),
) {
    /** The user typed [next]; it is shown immediately and awaits the owner's confirmation. */
    fun edited(next: String): ChecklistTextDraft =
        if (next == text) this else copy(text = next, pending = (pending + next).takeLast(MAX_REMEMBERED_EDITS))

    /** The owner published [value]: acknowledges pending edits, ignores stale echoes, otherwise adopts it when idle. */
    fun published(value: String): ChecklistTextDraft {
        if (value == published) return this
        val acknowledged = pending.indexOf(value)
        return when {
            acknowledged >= 0 -> copy(
                published = value,
                pending = pending.drop(acknowledged + 1),
                settled = (settled + published + pending.take(acknowledged + 1)).takeLast(MAX_REMEMBERED_EDITS),
            )

            value in settled -> this

            pending.isEmpty() -> ChecklistTextDraft(value)

            else -> copy(published = value)
        }
    }
}
