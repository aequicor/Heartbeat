package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard

/** Text as the composer displays it: every project skill link reads as its label. */
internal fun composerReferenceDisplay(text: CharSequence): String =
    COMPOSER_SKILL_LINK.replace(text) { it.groupValues[1] }

/**
 * Raw text removed by the latest composer edit, widened to whole skill links whose labels it covers. Foundation
 * cuts the displayed text, so the clipboard needs the removed markup recorded before the edit lands. A record serves
 * at most one clipboard write and is forgotten once the user selects text again: a later copy of the remaining draft
 * can then never resolve to a link that was deleted earlier.
 */
internal class ComposerReferenceRemovals {
    private var latest: Removal? = null

    fun record(original: CharSequence, removed: IntRange?) {
        latest = removed?.let { range ->
            val links = COMPOSER_SKILL_LINK.findAll(original).filter { link ->
                val label = requireNotNull(link.groups[1]).range
                label.first >= range.first && label.last <= range.last
            }
            val from = minOf(range.first, links.minOfOrNull { it.range.first } ?: range.first)
            val until = maxOf(range.last, links.maxOfOrNull { it.range.last } ?: range.last) + 1
            Removal(original.subSequence(from, until).toString(), from, original.removeRange(from, until).toString())
        }
    }

    fun forget() {
        latest = null
    }

    /** Raw text of the cut that produced exactly [text] with the caret at [caret], when it displays as [shown]. */
    fun cut(text: CharSequence, caret: Int, shown: String): String? = latest
        ?.takeIf { it.at == caret && it.after.contentEquals(text) && composerReferenceDisplay(it.raw) == shown }
        ?.raw

    private class Removal(val raw: String, val at: Int, val after: String)
}

/**
 * Clipboard of the composer editor. Foundation copies and cuts the displayed text, where a project skill link shows
 * only its label; the copied range is mapped back to the raw draft so whole links keep their instruction path when
 * pasted. Text that cannot be read back or does not cover a whole link passes through unchanged.
 */
internal class ComposerReferenceClipboard(
    private val delegate: Clipboard,
    private val state: TextFieldState,
    private val removals: ComposerReferenceRemovals,
) : Clipboard {
    // Paste availability checks platform clip metadata through the native clipboard; the default would throw.
    @Deprecated("Use platform-specific extension to get platform reference")
    @OptIn(ExperimentalComposeUiApi::class)
    @Suppress("DEPRECATION") // The delegate exposes the same deprecated platform reference.
    override val nativeClipboard: NativeClipboard
        get() = delegate.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    /** Forgets the last removal whenever the user selects text, which only a copy can follow. */
    suspend fun watchSelection() {
        snapshotFlow { state.selection.collapsed }.collect { isCollapsed -> if (!isCollapsed) removals.forget() }
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val shown = clipEntry?.let(::hbClipEntryPlainText)
        val raw = shown?.let(::rawText)
        removals.forget()
        delegate.setClipEntry(if (raw == null || raw == shown) clipEntry else hbPlainTextClipEntry(raw))
    }

    /**
     * A cut has already removed the copied text and left the caret where it was, so its exact recorded removal wins;
     * copy leaves the draft intact with the caret at the copied end. If the user edits before a cut's clipboard
     * write, neither matches and the displayed text is kept.
     */
    private fun rawText(shown: String): String? {
        val text = state.text
        val caret = state.selection.max
        removals.cut(text, caret, shown)?.let { return it }
        val ends = listOf(caret) + COMPOSER_SKILL_LINK.findAll(text)
            .filter { requireNotNull(it.groups[1]).range.last + 1 == caret }
            .map { it.range.last + 1 }
        // Hidden markup bounds how far before the visible length the raw start can lie; the widest match wins.
        val hidden = text.length - composerReferenceDisplay(text).length
        return ends.sortedDescending().firstNotNullOfOrNull { end ->
            val latest = end - shown.length
            if (latest < 0) return@firstNotNullOfOrNull null
            ((latest - hidden).coerceAtLeast(0)..latest).firstOrNull { start ->
                composerReferenceDisplay(text.subSequence(start, end)) == shown
            }?.let { start -> text.subSequence(start, end).toString() }
        }
    }
}
