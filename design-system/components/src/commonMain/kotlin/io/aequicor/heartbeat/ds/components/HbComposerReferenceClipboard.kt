package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard

/** Text as the composer displays it: every project skill link reads as its label. */
internal fun composerReferenceDisplay(text: CharSequence): String =
    COMPOSER_SKILL_LINK.replace(text) { it.groupValues[1] }

/**
 * Raw text removed by the latest composer edit, widened to whole skill links whose labels it covers. Foundation
 * cuts the displayed text, so the clipboard needs the removed markup recorded before the edit lands.
 */
internal class ComposerReferenceRemovals {
    var latest: String? = null
        private set

    fun record(original: CharSequence, removed: IntRange?) {
        latest = removed?.let { range ->
            val links = COMPOSER_SKILL_LINK.findAll(original).filter { link ->
                val label = requireNotNull(link.groups[1]).range
                label.first >= range.first && label.last <= range.last
            }
            val from = minOf(range.first, links.minOfOrNull { it.range.first } ?: range.first)
            val until = maxOf(range.last, links.maxOfOrNull { it.range.last } ?: range.last) + 1
            original.subSequence(from, until).toString()
        }
    }
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

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val shown = clipEntry?.let(::hbClipEntryPlainText)
        val raw = shown?.let(::rawText)
        delegate.setClipEntry(if (raw == null || raw == shown) clipEntry else hbPlainTextClipEntry(raw))
    }

    /** Copy leaves the draft intact with the caret at the copied end; cut has already removed the copied text. */
    private fun rawText(shown: String): String? {
        val text = state.text
        val caret = state.selection.max
        val ends = listOf(caret) + COMPOSER_SKILL_LINK.findAll(text)
            .filter { requireNotNull(it.groups[1]).range.last + 1 == caret }
            .map { it.range.last + 1 }
        // Hidden markup bounds how far before the visible length the raw start can lie; the widest match wins.
        val hidden = text.length - composerReferenceDisplay(text).length
        val copied = ends.sortedDescending().firstNotNullOfOrNull { end ->
            val latest = end - shown.length
            if (latest < 0) return@firstNotNullOfOrNull null
            ((latest - hidden).coerceAtLeast(0)..latest).firstOrNull { start ->
                composerReferenceDisplay(text.subSequence(start, end)) == shown
            }?.let { start -> text.subSequence(start, end).toString() }
        }
        return copied ?: removals.latest?.takeIf { composerReferenceDisplay(it) == shown }
    }
}
