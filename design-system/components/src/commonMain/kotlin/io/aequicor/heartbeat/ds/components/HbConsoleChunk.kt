package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** One bounded console segment; raw text is retained independently of the display boundary newline. */
@Immutable
internal data class HbConsoleChunk(
    val text: String,
    val spans: ImmutableList<HbConsoleSpan>,
    val isFirst: Boolean,
    val isLast: Boolean,
) {
    val displayText: String = if (isLast || !text.endsWith('\n')) {
        text
    } else {
        text.dropLast(if (text.endsWith("\r\n")) 2 else 1)
    }
}

/** Classify complete lines before slicing so continuations inherit the original line's tone. */
internal fun chunkHbConsole(source: String): ImmutableList<HbConsoleChunk> {
    val spans = highlightHbConsole(source)
    val chunks = chunkHbText(HbMarkdownText(source))
    var offset = 0
    var spanIndex = 0
    return chunks.mapIndexed { chunkIndex, chunk ->
        val end = offset + chunk.text.length
        while (spanIndex < spans.size && spans[spanIndex].end <= offset) spanIndex++
        val visibleSpans = mutableListOf<HbConsoleSpan>()
        var index = spanIndex
        while (index < spans.size && spans[index].start < end) {
            val span = spans[index]
            visibleSpans += span.copy(start = maxOf(span.start, offset) - offset, end = minOf(span.end, end) - offset)
            index++
        }
        offset = end
        HbConsoleChunk(chunk.text, visibleSpans.toImmutableList(), chunkIndex == 0, chunkIndex == chunks.lastIndex)
    }.toImmutableList()
}
