package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** One bounded segment of a file diff; raw source and the display boundary newline are separate. */
@Immutable
internal data class HbDiffChunk(
    val text: String,
    val filePath: String?,
    val isFirst: Boolean,
    val isLast: Boolean,
    val lineTones: ImmutableList<HbTone>,
) {
    val displayText: String = diffDisplayText(text, isLast)
}

/** Keep all source lines while resolving complete file headers before bounded UTF-16-safe splitting. */
internal fun chunkHbDiff(source: String): ImmutableList<HbDiffChunk> {
    val parser = HbDiffParser(source)
    val files = parser.parse()
    val tones = HbDiffLineTones(source, parser::isFileHeader)
    return files.flatMap { file ->
        val chunks = chunkHbText(HbMarkdownText(source.substring(file.start, file.end)))
        var offset = file.start
        chunks.mapIndexed { index, chunk ->
            val isLast = index == chunks.lastIndex
            val lineTones = tones.slice(offset, diffDisplayText(chunk.text, isLast))
            offset += chunk.text.length
            HbDiffChunk(chunk.text, file.path, index == 0, isLast, lineTones)
        }
    }.toImmutableList()
}

private fun diffDisplayText(text: String, isLast: Boolean): String = if (isLast || !text.endsWith('\n')) {
    text
} else {
    text.dropLast(if (text.endsWith("\r\n")) 2 else 1)
}
