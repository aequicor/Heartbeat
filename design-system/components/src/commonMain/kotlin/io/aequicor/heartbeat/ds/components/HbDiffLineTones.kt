package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Resolve source lines once so bounded fragments cannot acquire a different diff marker. */
internal class HbDiffLineTones(source: String, isFileHeader: (Int) -> Boolean) {
    private val starts = mutableListOf(0)
    private val tones = mutableListOf(sourceTone(source, 0, isFileHeader))
    private var cursor = 0

    init {
        source.forEachIndexed { index, character ->
            if (endsDiffLine(source, index, character)) {
                starts += index + 1
                tones += sourceTone(source, index + 1, isFileHeader)
            }
        }
    }

    fun slice(offset: Int, displayText: String): ImmutableList<HbTone> {
        val result = mutableListOf(toneAt(offset))
        displayText.forEachIndexed { index, character ->
            if (endsDiffLine(displayText, index, character)) result += toneAt(offset + index + 1)
        }
        return result.toImmutableList()
    }

    private fun toneAt(offset: Int): HbTone {
        while (cursor < starts.lastIndex && starts[cursor + 1] <= offset) cursor++
        return tones[cursor]
    }
}

private fun endsDiffLine(source: String, index: Int, character: Char): Boolean =
    character == '\n' || (character == '\r' && source.getOrNull(index + 1) != '\n')

private fun sourceTone(source: String, start: Int, isFileHeader: (Int) -> Boolean): HbTone = when {
    isFileHeader(start) -> HbTone.Neutral
    source.getOrNull(start) == '+' -> HbTone.Success
    source.getOrNull(start) == '-' -> HbTone.Danger
    source.startsWith("@@", start) -> HbTone.Brand
    else -> HbTone.Neutral
}
