package io.aequicor.heartbeat.ds.components

internal data class HbDiffFile(val start: Int, val end: Int, val path: String?)

/** Small unified-diff reader; hunk counts prevent removed/added header-like text becoming file names. */
internal class HbDiffParser(private val source: String) {
    private val files = mutableListOf<HbDiffFile>()
    private val headerOffsets = mutableSetOf<Int>()
    private var fileStart = 0
    private var filePath: String? = null
    private var hasFileHeaders = false
    private var hunk: DiffHunk? = null

    fun parse(): List<HbDiffFile> {
        var position = 0
        while (position < source.length) {
            val line = lineAt(position)
            position = readLine(line)
        }
        if (fileStart < source.length || files.isEmpty()) files += HbDiffFile(fileStart, source.length, filePath)
        return files
    }

    fun isFileHeader(offset: Int): Boolean = offset in headerOffsets

    private fun readLine(line: DiffLine): Int {
        when {
            FileStartPrefixes.any { line.text.startsWith(it) } -> beginFile(line.start)

            line.text.startsWith("@@") -> hunk = parseHunk(line.text)

            // Inexact counts (hand-written or model-written patches) must not swallow the next file.
            hunk != null && !startsFileHeaders(line) -> consumeHunkLine(line.text)

            line.text.startsWith("--- ") -> {
                hunk = null
                return readFileHeaders(line)
            }
        }
        return line.end
    }

    /** A `---`/`+++` pair followed by `@@` is a new file: hunk body lines never start with `@@`. */
    private fun startsFileHeaders(old: DiffLine): Boolean {
        if (!old.text.startsWith("--- ") || old.end >= source.length) return false
        val new = lineAt(old.end)
        if (!new.text.startsWith("+++ ") || new.end >= source.length) return false
        return lineAt(new.end).text.startsWith("@@")
    }

    private fun readFileHeaders(old: DiffLine): Int {
        if (old.end >= source.length) return old.end
        val new = lineAt(old.end)
        if (!new.text.startsWith("+++ ")) return old.end
        if (hasFileHeaders) beginFile(old.start)
        filePath = diffFilePath(old.text.removePrefix("--- "), new.text.removePrefix("+++ "))
        headerOffsets += old.start
        headerOffsets += new.start
        hasFileHeaders = true
        return new.end
    }

    private fun beginFile(start: Int) {
        if (start > fileStart) files += HbDiffFile(fileStart, start, filePath)
        fileStart = start
        filePath = null
        hasFileHeaders = false
        hunk = null
    }

    private fun consumeHunkLine(line: String) {
        // Editors often strip the leading space of blank context lines; GNU patch counts them as context.
        hunk?.consume(line.firstOrNull() ?: ' ')
        if (hunk?.isComplete == true) hunk = null
    }

    private fun lineAt(start: Int): DiffLine {
        val newline = source.indexOf('\n', start)
        val end = if (newline < 0) source.length else newline + 1
        return DiffLine(start, end, source.substring(start, end).trimEnd('\r', '\n'))
    }
}

private data class DiffLine(val start: Int, val end: Int, val text: String)

private class DiffHunk(private var oldRemaining: Int?, private var newRemaining: Int?) {
    val isComplete: Boolean get() = oldRemaining == 0 && newRemaining == 0

    fun consume(prefix: Char?) {
        if (prefix == ' ' || prefix == '-') oldRemaining = oldRemaining?.let { (it - 1).coerceAtLeast(0) }
        if (prefix == ' ' || prefix == '+') newRemaining = newRemaining?.let { (it - 1).coerceAtLeast(0) }
    }
}

private fun parseHunk(line: String): DiffHunk? {
    val match = HunkHeader.find(line) ?: return DiffHunk(null, null)
    val oldCount = match.groupValues[1].ifEmpty { "1" }.toIntOrNull()
    val newCount = match.groupValues[2].ifEmpty { "1" }.toIntOrNull()
    return if (oldCount == 0 && newCount == 0) null else DiffHunk(oldCount, newCount)
}

private val FileStartPrefixes = listOf("diff --git ", "diff --cc ", "diff --combined ")

private val HunkHeader = Regex("^@@ -[0-9]+(?:,([0-9]+))? \\+[0-9]+(?:,([0-9]+))? @@")
