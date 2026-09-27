package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal class HbCodeLexer(private val source: String, private val language: HbCodeLanguage) {
    private val spans = mutableListOf<HbCodeSpan>()
    private var position = 0

    fun scan(): ImmutableList<HbCodeSpan> {
        while (position < source.length) scanNext()
        return spans.toImmutableList()
    }

    private fun scanNext() {
        val character = source[position]
        when {
            language.hasSlashComments && source.startsWith("//", position) -> lineComment()
            language.hasSlashComments && source.startsWith("/*", position) -> blockComment()
            isHashComment(character) -> lineComment()
            character in language.quotes -> stringLiteral(character)
            isAnnotationStart(character) -> annotation()
            source.isHbCodeNumberStart(position, language) -> numberLiteral()
            character == '`' && language == HbCodeLanguage.Kotlin -> quotedIdentifier()
            isIdentifierStart(position) -> identifier()
            else -> position++
        }
    }

    private fun isAnnotationStart(character: Char): Boolean =
        character == '@' && language.hasAnnotations && isIdentifierStart(position + 1)

    private fun isHashComment(character: Char): Boolean {
        if (character != '#') return false
        return when (language) {
            HbCodeLanguage.Python -> true

            HbCodeLanguage.Shell -> position == 0 || source[position - 1].isWhitespace() ||
                source[position - 1] in ";|&()"

            HbCodeLanguage.Kotlin, HbCodeLanguage.Java, HbCodeLanguage.JavaScript, HbCodeLanguage.Json -> false
        }
    }

    private fun lineComment() {
        val start = position
        while (position < source.length && source[position] != '\n' && source[position] != '\r') position++
        add(start, HbCodeTokenKind.Comment)
    }

    private fun blockComment() {
        val start = position
        position += COMMENT_DELIMITER_LENGTH
        var depth = 1
        while (position < source.length && depth > 0) {
            when {
                source.startsWith("*/", position) -> {
                    depth--
                    position += COMMENT_DELIMITER_LENGTH
                }

                language == HbCodeLanguage.Kotlin && source.startsWith("/*", position) -> {
                    depth++
                    position += COMMENT_DELIMITER_LENGTH
                }

                else -> position++
            }
        }
        add(start, HbCodeTokenKind.Comment)
    }

    private fun stringLiteral(quote: Char) {
        val start = position
        val tripleDelimiter = quote.toString().repeat(TRIPLE_QUOTE)
        val hasTripleQuote = supportsTripleQuote(quote) && source.startsWith(tripleDelimiter, position)
        val delimiter = quote.toString().repeat(if (hasTripleQuote) TRIPLE_QUOTE else 1)
        val hasEscapes = !(hasTripleQuote && language == HbCodeLanguage.Kotlin) &&
            !(quote == '\'' && language == HbCodeLanguage.Shell)
        position += delimiter.length
        consumeString(delimiter, hasEscapes)
        add(start, HbCodeTokenKind.String)
    }

    private fun supportsTripleQuote(quote: Char): Boolean = when (language) {
        HbCodeLanguage.Kotlin, HbCodeLanguage.Java -> quote == '"'
        HbCodeLanguage.Python -> true
        HbCodeLanguage.JavaScript, HbCodeLanguage.Json, HbCodeLanguage.Shell -> false
    }

    private fun consumeString(delimiter: String, hasEscapes: Boolean) {
        while (position < source.length) {
            when {
                hasEscapes && source[position] == '\\' -> position += minOf(ESCAPE_LENGTH, source.length - position)

                source.startsWith(delimiter, position) -> {
                    position += delimiter.length
                    return
                }

                else -> position++
            }
        }
    }

    private fun annotation() {
        val start = position++
        while (position < source.length && (isIdentifierPart(position) || source[position] in ".:")) position++
        add(start, HbCodeTokenKind.Annotation)
    }

    private fun numberLiteral() {
        val start = position
        position = source.hbCodeNumberEnd(position, language)
        add(start, HbCodeTokenKind.Number)
    }

    private fun identifier() {
        val start = position++
        while (position < source.length && isIdentifierPart(position)) position++
        val name = source.substring(start, position)
        val kind = when {
            name in language.keywords -> HbCodeTokenKind.Keyword
            !language.hasIdentifierRoles -> null
            hasFollowingParenthesis() -> HbCodeTokenKind.Function
            name.first().isUpperCase() -> HbCodeTokenKind.Type
            else -> null
        }
        if (kind != null) add(start, kind)
    }

    private fun quotedIdentifier() {
        val start = position++
        while (position < source.length && source[position] != '`' && source[position] != '\n') position++
        if (source.getOrNull(position) == '`') position++
        if (hasFollowingParenthesis()) add(start, HbCodeTokenKind.Function)
    }

    private fun hasFollowingParenthesis(): Boolean {
        var next = position
        while (next < source.length && source[next].isWhitespace()) next++
        return source.getOrNull(next) == '('
    }

    private fun isIdentifierStart(index: Int): Boolean {
        val character = source.getOrNull(index) ?: return false
        return character.isLetter() || character == '_' || (character == '$' && language == HbCodeLanguage.JavaScript)
    }

    private fun isIdentifierPart(index: Int): Boolean {
        val character = source[index]
        return isIdentifierStart(index) || character.isDigit() || character.category in IdentifierMarks
    }

    private fun add(start: Int, kind: HbCodeTokenKind) {
        spans += HbCodeSpan(start, position, kind)
    }
}

private const val COMMENT_DELIMITER_LENGTH = 2
private const val ESCAPE_LENGTH = 2
private const val TRIPLE_QUOTE = 3
private val IdentifierMarks = setOf(
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
)
