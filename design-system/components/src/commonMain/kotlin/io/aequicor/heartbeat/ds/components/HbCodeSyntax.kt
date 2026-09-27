package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Semantic syntax roles; presentation colors belong to the theme. */
public enum class HbCodeTokenKind { Keyword, String, Number, Comment, Type, Function, Annotation }

/** An exclusive-end UTF-16 range in the unchanged code source. */
@Immutable
public data class HbCodeSpan(val start: Int, val end: Int, val kind: HbCodeTokenKind) {
    init {
        require(start >= 0 && end > start) { "A code span must be a non-empty positive range." }
    }
}

/**
 * Scans supported fence languages in linear time, returning ordered, non-overlapping UTF-16 ranges.
 * Unknown, absent and plain-text language hints return no spans; the source is never rewritten.
 * Incomplete strings and comments extend to the available input boundary while streaming.
 *
 * This is a display lexer, not a compiler: interpolation remains part of its string, and regular
 * expression literals, JSX markup, Python string prefixes and shell heredocs have no grammar parsing.
 * Identifiers followed by an opening parenthesis are calls, including constructors; remaining
 * capitalized identifiers are treated as types. Fence hints use their first whitespace-separated word.
 */
public fun highlightHbCode(source: String, language: String?): ImmutableList<HbCodeSpan> {
    val syntax = hbCodeLanguage(language) ?: return persistentListOf()
    return HbCodeLexer(source, syntax).scan()
}
