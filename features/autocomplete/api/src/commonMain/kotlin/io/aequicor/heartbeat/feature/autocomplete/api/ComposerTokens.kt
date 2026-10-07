package io.aequicor.heartbeat.feature.autocomplete.api

/**
 * The composer token the caret is editing, or null when the caret is outside any trigger.
 *
 * A command token is a `/` word at the start of a line; a mention is an `@` word that begins after whitespace
 * or at the start of a line and carries at least one typed character. Words end at whitespace, so a `/` inside
 * a URL (`https://…`) or an address (`a@b`) never triggers. [ComposerTrigger.range] spans the whole word,
 * while [ComposerTrigger.query] is only what the user typed before the caret — accepting a suggestion replaces
 * the whole word.
 */
public fun composerTrigger(text: String, caret: Int): ComposerTrigger? {
    val position = caret.coerceIn(0, text.length)
    if (position == 0 || text[position - 1].isWhitespace()) return null
    var start = position - 1
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    var end = position
    while (end < text.length && !text[end].isWhitespace()) end++
    val word = text.substring(start, end)
    val queryEnd = position
    val isAtLineStart = start == 0 || text[start - 1] == '\n'
    return when (word.first()) {
        '/' -> if (isAtLineStart) {
            ComposerTrigger.Command(start..(end - 1), text.substring(start + 1, queryEnd))
        } else {
            null
        }

        '@' -> if (queryEnd <= start + 1) {
            null
        } else {
            ComposerTrigger.Mention(start..(end - 1), text.substring(start + 1, queryEnd))
        }

        else -> null
    }
}
