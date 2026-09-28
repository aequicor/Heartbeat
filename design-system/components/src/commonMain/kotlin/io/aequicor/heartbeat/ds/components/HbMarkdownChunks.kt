package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal const val MARKDOWN_CHUNK_CHARACTERS = 2048
private const val MARKDOWN_CHUNK_LINES = 24

internal fun HbMarkdownText.trimmed(): HbMarkdownText {
    val start = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val end = (text.indexOfLast { !it.isWhitespace() } + 1).coerceAtLeast(start)
    return HbMarkdownText(
        text = text.substring(start, end),
        spans = spans.mapNotNull { span ->
            val from = maxOf(start, span.start)
            val to = minOf(end, span.end)
            if (from < to) span.copy(start = from - start, end = to - start) else null
        }.toImmutableList(),
    )
}

/** Splits large payloads without losing content, cutting span ranges or splitting a surrogate pair. */
internal fun chunkHbText(value: HbMarkdownText, preferWordBoundary: Boolean = false): ImmutableList<HbMarkdownText> {
    val chunks = mutableListOf<HbMarkdownText>()
    var offset = 0
    while (offset < value.text.length) {
        val end = chunkEnd(value.text, offset, preferWordBoundary)
        val spans = value.spans.mapNotNull { span ->
            val start = maxOf(span.start, offset)
            val finish = minOf(span.end, end)
            if (start < finish) span.copy(start = start - offset, end = finish - offset) else null
        }.toImmutableList()
        chunks += HbMarkdownText(value.text.substring(offset, end), spans)
        offset = end
    }
    if (chunks.isEmpty()) chunks += value
    return chunks.toImmutableList()
}

private fun chunkEnd(text: String, start: Int, preferWordBoundary: Boolean): Int {
    var end = minOf(start + MARKDOWN_CHUNK_CHARACTERS, text.length)
    var lines = 0
    for (index in start until end) {
        if (text[index] == '\n') lines++
        if (lines == MARKDOWN_CHUNK_LINES) {
            end = index + 1
            break
        }
    }
    if (preferWordBoundary) end = wordBoundary(text, start, end)
    if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    if (end < text.length && text[end - 1] == '\r' && text[end] == '\n') end--
    return end
}

private fun wordBoundary(text: String, start: Int, end: Int): Int {
    if (end == text.length || text[end - 1].isWhitespace()) return end
    val boundary = (end - 1 downTo start + (end - start) / 2).firstOrNull { text[it].isWhitespace() }
    return boundary?.plus(1) ?: end
}

/** A following lazy row supplies the boundary line break; do not paint an extra empty line above it. */
internal fun HbMarkdownText.withoutTrailingLineBreak(): HbMarkdownText {
    val display = text.withoutTrailingLineBreak()
    return if (display.length == text.length) {
        this
    } else {
        copy(
            text = display,
            spans = spans.mapNotNull { span ->
                val end = minOf(span.end, display.length)
                if (span.start < end) span.copy(end = end) else null
            }.toImmutableList(),
        )
    }
}
