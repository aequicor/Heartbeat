package io.aequicor.heartbeat.feature.computeruse.impl.data

/**
 * One piece of a typed string: either a run of characters or a control key that must arrive as a key press.
 * Splitting lets the Unicode backend carry the characters untouched while newline, tab and backspace keep their key
 * semantics (focus moves, submit, erase) on every host, which plain character events would not provide.
 */
internal sealed interface TextSegment {
    /** Characters typed as they are, without any key semantics. */
    data class Literal(val text: String) : TextSegment

    /** A newline (`\n`, `\r` or `\r\n`): closes the current input or submits the form. */
    data object Newline : TextSegment

    /** A tab: moves focus between controls. */
    data object Tab : TextSegment

    /** A backspace: erases the previous character. */
    data object Backspace : TextSegment
}

/**
 * Splits typed text into literal runs and control keys; literal runs are never empty. Any line ending becomes one
 * [TextSegment.Newline], so CRLF text does not break lines twice. Other control characters stay in literal runs
 * for the caller to refuse.
 */
internal fun textSegments(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    val literal = StringBuilder()
    for ((index, character) in text.withIndex()) {
        if (character == '\n' && index > 0 && text[index - 1] == '\r') continue
        val control = controlSegment(character)
        if (control == null) {
            literal.append(character)
        } else {
            if (literal.isNotEmpty()) {
                segments += TextSegment.Literal(literal.toString())
                literal.clear()
            }
            segments += control
        }
    }
    if (literal.isNotEmpty()) segments += TextSegment.Literal(literal.toString())
    return segments
}

private fun controlSegment(character: Char): TextSegment? = when (character) {
    '\n', '\r' -> TextSegment.Newline
    '\t' -> TextSegment.Tab
    '\b' -> TextSegment.Backspace
    else -> null
}
