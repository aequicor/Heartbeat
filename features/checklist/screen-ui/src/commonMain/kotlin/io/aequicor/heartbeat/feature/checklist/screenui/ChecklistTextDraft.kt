package io.aequicor.heartbeat.feature.checklist.screenui

import androidx.compose.runtime.Immutable

/**
 * Own edits remembered per field; older ones are dropped first. An edit the owner silently ignores stays in
 * [ChecklistTextDraft.pending] until the user edits again or it is dropped, and meanwhile other values from the owner
 * do not replace the text.
 */
private const val MAX_REMEMBERED_EDITS = 64

/**
 * Local editing state of one text field whose owner (the profile machine and its durable journal, possibly behind a
 * remote panel) publishes drafts asynchronously and in order, possibly skipping intermediate values.
 *
 * [text] is what the editor shows. Every user edit becomes [text] at once and stays [pending] until the owner
 * publishes it, so late answers never overwrite newer typing. A published value acknowledges its edit and every
 * older one. [echoes] are own sent edits that need no answer but may still be published in order (edits up to and
 * including one that returned to the last published text, or values between two equal edits); they are consumed as
 * they arrive and forgotten once a later pending edit is answered.
 * Any other published value replaces [text] when no edit is in flight, so a change from another editor of the same
 * card or a reset of a remote panel wins once the user's input has been answered.
 *
 * Without owner sequence numbers an echo the owner never shows (conflated away) is indistinguishable from one still in
 * flight: until the next answered edit, another editor's value equal to such an echo is not adopted.
 */
@Immutable
internal data class ChecklistTextDraft(
    val text: String,
    val published: String = text,
    val pending: List<String> = emptyList(),
    val echoes: List<String> = emptyList(),
) {
    /** The user typed [next]; it is shown immediately and awaits the owner's confirmation. */
    fun edited(next: String): ChecklistTextDraft = when (next) {
        text -> this

        // The owner already shows this text: nothing to answer, but it may still publish the in-flight edits and this
        // one in order.
        published -> copy(
            text = next,
            pending = emptyList(),
            echoes = (echoes + pending + next).takeLast(MAX_REMEMBERED_EDITS),
        )

        // Older echoes stay: they precede this edit and are dropped once it, or a later edit, is answered.
        else -> copy(text = next, pending = (pending + next).takeLast(MAX_REMEMBERED_EDITS))
    }

    /** The owner published [value]: acknowledges pending edits, consumes own echoes, otherwise adopts it when idle. */
    fun published(value: String): ChecklistTextDraft {
        if (value == published) return this
        // Echoes were sent before every pending edit: together they are all edits the owner may still publish.
        val sent = echoes + pending
        val earliest = sent.indexOf(value)
        if (earliest < 0) return if (pending.isEmpty()) ChecklistTextDraft(value) else copy(published = value)
        val latest = sent.lastIndexOf(value)
        // The owner answered one of the equal edits: those after the earliest may still arrive but need no answer,
        // edits after the latest keep their role.
        val answered = sent.slice(earliest + 1..latest)
        val laterEchoes = (echoes.size - latest - 1).coerceAtLeast(0)
        return copy(
            published = value,
            pending = pending.takeLast(sent.size - latest - 1 - laterEchoes),
            echoes = (answered + echoes.takeLast(laterEchoes)).takeLast(MAX_REMEMBERED_EDITS),
        )
    }
}
