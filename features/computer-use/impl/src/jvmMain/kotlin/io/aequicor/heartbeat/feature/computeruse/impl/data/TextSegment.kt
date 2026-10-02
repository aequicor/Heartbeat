package io.aequicor.heartbeat.feature.computeruse.impl.data

/**
 * One piece of a typed string: either a run of characters or a control key that must arrive as a key press.
 * Splitting lets the Unicode backend carry the characters untouched while newline and tab keep their key
 * semantics (focus moves, submit), which plain character events would not provide.
 */
internal sealed interface TextSegment {
    /** Characters typed as they are, without any key semantics. */
    data class Literal(val text: String) : TextSegment

    /** A newline: closes the current input or submits the form. */
    data object Newline : TextSegment

    /** A tab: moves focus between controls. */
    data object Tab : TextSegment
}

/** Splits typed text into literal runs and control keys; literal runs are never empty. */
internal fun textSegments(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    val literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) {
            segments += TextSegment.Literal(literal.toString())
            literal.clear()
        }
    }
    for (character in text) {
        when (character) {
            '\n' -> {
                flush()
                segments += TextSegment.Newline
            }

            '\t' -> {
                flush()
                segments += TextSegment.Tab
            }

            else -> literal.append(character)
        }
    }
    flush()
    return segments
}
