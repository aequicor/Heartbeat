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
 * older one. [echoes] are own edits that need no answer but may still be published in order (an edit returned to the
 * published text, or values between two equal edits); they are consumed as they arrive and forgotten on the next edit.
 * Any other published value replaces [text] when no edit is in flight, so a change from another editor of the same
 * card or a reset of a remote panel wins once the user's input has been answered.
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

        // The owner already holds this text: nothing to answer, but the in-flight edits may still be published.
        published -> copy(
            text = next,
            pending = emptyList(),
            echoes = (echoes + pending).takeLast(MAX_REMEMBERED_EDITS),
        )

        else -> copy(text = next, pending = (pending + next).takeLast(MAX_REMEMBERED_EDITS), echoes = emptyList())
    }

    /** The owner published [value]: acknowledges pending edits, consumes own echoes, otherwise adopts it when idle. */
    fun published(value: String): ChecklistTextDraft {
        if (value == published) return this
        val acknowledged = pending.lastIndexOf(value)
        val echo = echoes.indexOf(value)
        return when {
            acknowledged >= 0 -> {
                // With equal edits the owner may have answered the earlier one: values between them can still come.
                val earliest = pending.indexOf(value)
                copy(
                    published = value,
                    pending = pending.drop(acknowledged + 1),
                    echoes = pending.slice(earliest + 1 until acknowledged),
                )
            }

            echo >= 0 -> copy(published = value, echoes = echoes.drop(echo + 1))

            pending.isEmpty() -> ChecklistTextDraft(value)

            else -> copy(published = value)
        }
    }
}
