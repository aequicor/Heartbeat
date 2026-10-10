package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.text.TextRange

/** One indentation step of the code editor. */
internal const val HB_CODE_INDENT_UNIT: String = "    "

/** Text and selection after an editor command; the selection keeps its direction. */
internal data class HbCodeEdit(val text: String, val selection: TextRange)

/**
 * Indents with [HB_CODE_INDENT_UNIT]. A caret inserts one indent at its position. A selection indents every line it
 * touches, except a last line it only reaches at column 0; when it touches several lines, empty lines stay empty.
 * Selection endpoints move with their text, while an endpoint at the start of a line keeps the new indent selected.
 */
internal fun indentHbCode(text: String, selection: TextRange): HbCodeEdit {
    if (selection.collapsed) {
        val caret = selection.start
        val indented = text.substring(0, caret) + HB_CODE_INDENT_UNIT + text.substring(caret)
        return HbCodeEdit(indented, TextRange(caret + HB_CODE_INDENT_UNIT.length))
    }
    val lines = touchedLineStarts(text, selection)
    val starts = if (lines.size > 1) lines.filterNot { text.isEmptyLineAt(it) } else lines
    val indented = buildString(text.length + starts.size * HB_CODE_INDENT_UNIT.length) {
        var copied = 0
        starts.forEach { start ->
            append(text, copied, start)
            append(HB_CODE_INDENT_UNIT)
            copied = start
        }
        append(text, copied, text.length)
    }
    fun moved(offset: Int) = offset + HB_CODE_INDENT_UNIT.length * starts.count { it < offset }
    return HbCodeEdit(indented, TextRange(moved(selection.start), moved(selection.end)))
}

/**
 * Removes one indent from every line the caret or selection touches (same lines as [indentHbCode], empty lines
 * included): up to [HB_CODE_INDENT_UNIT] leading spaces, or a single leading tab.
 * Returns null when no line is indented.
 */
internal fun outdentHbCode(text: String, selection: TextRange): HbCodeEdit? {
    val removals = touchedLineStarts(text, selection)
        .map { start -> LineRemoval(start, text.leadingIndentAt(start)) }
        .filter { it.length > 0 }
    if (removals.isEmpty()) return null
    val outdented = buildString(text.length) {
        var copied = 0
        removals.forEach { removal ->
            append(text, copied, removal.start)
            copied = removal.start + removal.length
        }
        append(text, copied, text.length)
    }
    fun moved(offset: Int) = offset - removals.sumOf { removal ->
        if (removal.start >= offset) 0 else minOf(removal.length, offset - removal.start)
    }
    return HbCodeEdit(outdented, TextRange(moved(selection.start), moved(selection.end)))
}

/** Replacing `old[start, oldEnd)` with [replacement] turns the old text into the new one. */
internal data class HbCodeChange(val start: Int, val oldEnd: Int, val replacement: String)

/** The smallest single replacement between two texts: everything between their common prefix and suffix. */
internal fun hbCodeChange(old: String, new: String): HbCodeChange {
    val prefix = old.commonPrefixWith(new).length
    val limit = minOf(old.length, new.length) - prefix
    var suffix = 0
    while (suffix < limit && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return HbCodeChange(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
}

/** Lines shown by the editor gutter: one more than the line feeds, so an empty text has one line. */
internal fun hbCodeLineCount(text: String): Int = text.count { it == '\n' } + 1

private class LineRemoval(val start: Int, val length: Int)

/** Starts of the lines a caret or selection touches; a selection ending at column 0 leaves that line out. */
private fun touchedLineStarts(text: String, selection: TextRange): List<Int> {
    val first = text.lastIndexOf('\n', selection.min - 1) + 1
    val starts = mutableListOf(first)
    for (index in first until selection.max) {
        if (text[index] == '\n' && index + 1 < selection.max) starts += index + 1
    }
    return starts
}

private fun String.isEmptyLineAt(start: Int): Boolean = start == length || this[start] == '\n' || this[start] == '\r'

private fun String.leadingIndentAt(start: Int): Int {
    if (getOrNull(start) == '\t') return 1
    var spaces = 0
    while (spaces < HB_CODE_INDENT_UNIT.length && getOrNull(start + spaces) == ' ') spaces++
    return spaces
}
