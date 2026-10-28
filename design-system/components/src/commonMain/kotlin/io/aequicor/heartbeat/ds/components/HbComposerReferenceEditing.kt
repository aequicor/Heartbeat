package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.ui.text.TextRange

/**
 * An edit to a reference detaches its old target; ordinary edits outside references leave them intact. A pure
 * deletion is reported to [removals] first, so a cut can put the whole raw link on the clipboard.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun composerReferenceEditing(removals: ComposerReferenceRemovals? = null): InputTransformation =
    InputTransformation {
        val deleted = changes.takeIf { it.changeCount == 1 }?.let { change ->
            val original = change.getOriginalRange(0)
            if (change.getRange(0).collapsed && !original.collapsed) original.min until original.max else null
        }
        removals?.record(originalText, deleted)
        val edits = (0 until changes.changeCount).map { index ->
            ReferenceEdit(changes.getOriginalRange(index), changes.getRange(index))
        }.sortedBy { it.original.min }
        val markup = COMPOSER_SKILL_LINK.findAll(originalText).filter { link ->
            edits.any { it.touches(link.range, asCharSequence()) }
        }.flatMap { link ->
            val label = requireNotNull(link.groups[1])
            sequenceOf(link.range.first until label.range.first, (label.range.last + 1)..link.range.last)
        }.toList()
        // Only remove surviving old markup. Newly typed or pasted text must remain exactly as entered.
        val detached = mutableListOf<IntRange>()
        var originalStart = 0
        var currentStart = 0
        for (edit in edits) {
            detached += survivingMarkup(markup, originalStart, edit.original.min, currentStart)
            originalStart = edit.original.max
            currentStart = edit.current.max
        }
        detached += survivingMarkup(markup, originalStart, originalText.length, currentStart)
        for (range in detached.sortedByDescending { it.first }) replace(range.first, range.last + 1, "")
    }

private data class ReferenceEdit(val original: TextRange, val current: TextRange) {
    fun touches(link: IntRange, text: CharSequence): Boolean {
        if (!original.collapsed) return original.min <= link.last && original.max > link.first
        return when {
            original.start > link.first && original.start <= link.last -> true
            current.collapsed -> false
            original.start == link.first -> !text[current.max - 1].isWhitespace()
            original.start == link.last + 1 -> !text[current.min].isWhitespace()
            else -> false
        }
    }
}

/** Maps unchanged portions of hidden markup to their new positions, excluding every replaced character. */
private fun survivingMarkup(markup: List<IntRange>, start: Int, end: Int, currentStart: Int): List<IntRange> =
    markup.mapNotNull { range ->
        val from = maxOf(range.first, start)
        val until = minOf(range.last + 1, end)
        if (from < until) (from + currentStart - start) until (until + currentStart - start) else null
    }
