package io.aequicor.heartbeat.feature.autocomplete.api

/**
 * Applies [suggestion] to the draft at [trigger]: the whole token is replaced by the suggestion's insertion
 * text and the caret moves after it. A file suggestion additionally completes the typed token to the file's
 * full relative path and reports itself in [ComposerDraft.attach], so the owner imports it as an attachment
 * through its own durable input path.
 */
public fun applyComposerSuggestion(
    text: String,
    trigger: ComposerTrigger,
    suggestion: ComposerSuggestion,
): ComposerDraft {
    val insertion = when (suggestion) {
        is ComposerSuggestion.Command -> suggestion.insert

        is ComposerSuggestion.Skill -> suggestion.insert

        is ComposerSuggestion.File -> "@${suggestion.relativePath} "
    }
    val from = trigger.range.first
    val until = trigger.range.last + 1
    val next = StringBuilder(text.length - (until - from) + insertion.length)
        .append(text, 0, from)
        .append(insertion)
        .append(text, until, text.length)
        .toString()
    val caret = from + insertion.length
    return ComposerDraft(next, caret, (suggestion as? ComposerSuggestion.File))
}
