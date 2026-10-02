package io.aequicor.heartbeat.feature.aiengine.facade.api

private const val DIRECTIVE_OPEN = "<heartbeat-directive>"
private const val DIRECTIVE_CLOSE = "</heartbeat-directive>"
private val directiveBlock = Regex("\\s*<heartbeat-directive>[\\s\\S]*?</heartbeat-directive>")

/**
 * Wraps a host-authored instruction appended to a user prompt. The engine reads it as part of the message, while
 * every place that shows or replays user text removes it with [stripHostDirectives], so the transcript keeps only
 * what the user typed.
 */
public fun hostDirective(text: String): String = "$DIRECTIVE_OPEN\n${text.trim()}\n$DIRECTIVE_CLOSE"

/** Appends [directives] to [prompt] as separate host directive blocks; blank directives are skipped. */
public fun withHostDirectives(prompt: String, directives: List<String>): String =
    (listOf(prompt) + directives.filter(String::isNotBlank).map(::hostDirective))
        .filter(String::isNotBlank)
        .joinToString("\n\n")

/**
 * User text without host directive blocks and the whitespace that separated them; a block may precede the text
 * (so a prompt starting with a slash command is not read as a command by a CLI) or follow it.
 */
public fun stripHostDirectives(text: String): String =
    if (DIRECTIVE_OPEN in text) text.replace(directiveBlock, "").trim() else text
