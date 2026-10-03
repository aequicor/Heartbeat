package io.aequicor.heartbeat.feature.aiengine.facade.api

private const val DIRECTIVE_TAG = "heartbeat-directive"
private const val DIRECTIVE_OPEN = "<$DIRECTIVE_TAG>"
private const val DIRECTIVE_CLOSE = "</$DIRECTIVE_TAG>"
private const val DIRECTIVE_SEPARATOR = "\n\n"

// A directive tag inside user or directive text is sent escaped, so only host blocks read as directives.
private const val ESCAPED_OPEN = "&lt;$DIRECTIVE_TAG>"
private const val ESCAPED_CLOSE = "&lt;/$DIRECTIVE_TAG>"
private val directiveBlock = "${Regex.escape(DIRECTIVE_OPEN)}[\\s\\S]*?${Regex.escape(DIRECTIVE_CLOSE)}"
private val leadingBlocks = Regex("^(?:$directiveBlock(?:$DIRECTIVE_SEPARATOR|$))+")
private val trailingBlocks = Regex("(?:$DIRECTIVE_SEPARATOR$directiveBlock)+$")

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
 * tags the user typed are restored. Text without host blocks is returned unchanged.
 */
public fun stripHostDirectives(text: String): String {
    if (DIRECTIVE_OPEN !in text && ESCAPED_OPEN !in text && ESCAPED_CLOSE !in text) return text
    return text.replace(leadingBlocks, "")
        .replace(trailingBlocks, "")
        .replace(ESCAPED_OPEN, DIRECTIVE_OPEN)
        .replace(ESCAPED_CLOSE, DIRECTIVE_CLOSE)
}

private fun List<String>.blocks(): List<String> = filter(String::isNotBlank).map(::hostDirective)

private fun escaped(text: String): String =
    text.replace(DIRECTIVE_OPEN, ESCAPED_OPEN).replace(DIRECTIVE_CLOSE, ESCAPED_CLOSE)
