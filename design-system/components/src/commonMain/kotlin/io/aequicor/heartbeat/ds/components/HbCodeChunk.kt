package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
internal data class HbCodeChunk(val text: String, val spans: ImmutableList<HbCodeSpan>)

/** Tokenize once, then slice ordered ranges in one forward pass without losing multi-line context. */
internal fun chunkHbCode(source: String, language: String?): ImmutableList<HbCodeChunk> {
    val spans = highlightHbCode(source, language)
    var offset = 0
    var spanIndex = 0
    return chunkHbText(HbMarkdownText(source)).map { chunk ->
        val end = offset + chunk.text.length
        while (spanIndex < spans.size && spans[spanIndex].end <= offset) spanIndex++
        val visibleSpans = mutableListOf<HbCodeSpan>()
        var index = spanIndex
        while (index < spans.size && spans[index].start < end) {
            val span = spans[index]
            visibleSpans += span.copy(start = maxOf(span.start, offset) - offset, end = minOf(span.end, end) - offset)
            index++
        }
        offset = end
        HbCodeChunk(chunk.text, visibleSpans.toImmutableList())
    }.toImmutableList()
}
