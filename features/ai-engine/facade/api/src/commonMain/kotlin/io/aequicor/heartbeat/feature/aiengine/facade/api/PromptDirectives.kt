package io.aequicor.heartbeat.feature.aiengine.facade.api

private const val DIRECTIVE_TAG = "heartbeat-directive"
private const val DIRECTIVE_OPEN = "<$DIRECTIVE_TAG>"
private const val DIRECTIVE_CLOSE = "</$DIRECTIVE_TAG>"
private const val DIRECTIVE_SEPARATOR = "\n\n"

// A block never contains its closing tag (tags inside it are escaped), so a block cannot run into the next one.
private val directiveBlock =
    "${Regex.escape(DIRECTIVE_OPEN)}(?:(?!${Regex.escape(DIRECTIVE_CLOSE)})[\\s\\S])*${Regex.escape(DIRECTIVE_CLOSE)}"

// The host joins with an empty line; a history that rewrote line breaks (CRLF, a single break) still matches.
private const val SEPARATOR_PATTERN = "[ \\t]*\\r?\\n(?:[ \\t]*\\r?\\n)?"
private val leadingBlocks = Regex("^\\s*(?:$directiveBlock(?:$SEPARATOR_PATTERN|\\s*$))+")
private val trailingBlocks = Regex("(?:$SEPARATOR_PATTERN$directiveBlock)+\\s*$")

// A directive tag inside user or directive text is sent escaped, so only host blocks read as directives. Escaping
// adds one `amp;` to a tag the user typed already escaped, so restoring is exact for any typed text.
private val typedTag = Regex("<(/?)$DIRECTIVE_TAG>|&((?:amp;)*)lt;(/?)$DIRECTIVE_TAG>")
private val escapedTag = Regex("&((?:amp;)*)lt;(/?)$DIRECTIVE_TAG>")

/**
 * Wraps a host-authored instruction for a user prompt. The engine reads it as part of the message, while every place
 * that shows or replays user text removes it with [stripHostDirectives], so the transcript keeps only what the user
 * typed.
 */
public fun hostDirective(text: String): String = "$DIRECTIVE_OPEN\n${escaped(text.trim())}\n$DIRECTIVE_CLOSE"

/**
 * The prompt the engine receives for [prompt] typed by the user: [leading] directive blocks, the prompt, then
 * [directives]; blank directives are skipped. A directive tag the user typed is escaped, so it neither acts as a
 * host directive nor disappears from the transcript. A leading block keeps a prompt starting with a slash command
 * from being read as a command by a CLI.
 */
public fun withHostDirectives(prompt: String, directives: List<String>, leading: List<String> = emptyList()): String =
    (leading.blocks() + escaped(prompt) + directives.blocks())
        .filter(String::isNotBlank)
        .joinToString(DIRECTIVE_SEPARATOR)

/**
 * User text as typed: host directive blocks before and after it are removed with their separators, and directive
 * tags the user typed are restored. Text without host blocks or escaped tags is returned unchanged. Apply it to one
 * text part of a message at a time: blocks are recognised only at the edges of the text.
 */
public fun stripHostDirectives(text: String): String {
    if (DIRECTIVE_TAG !in text) return text
    val stripped = text.replace(leadingBlocks, "").replace(trailingBlocks, "")
    return stripped.replace(escapedTag) { match ->
        val amps = match.groupValues[1]
        val slash = match.groupValues[2]
        if (amps.isEmpty()) "<$slash$DIRECTIVE_TAG>" else "&${amps.removePrefix("amp;")}lt;$slash$DIRECTIVE_TAG>"
    }
}

private fun List<String>.blocks(): List<String> = filter(String::isNotBlank).map(::hostDirective)

private fun escaped(text: String): String = text.replace(typedTag) { match ->
    val (plainSlash, amps, escapedSlash) = match.destructured
    when {
        match.value.startsWith("<") -> "&lt;$plainSlash$DIRECTIVE_TAG>"
        else -> "&amp;${amps}lt;$escapedSlash$DIRECTIVE_TAG>"
    }
}
