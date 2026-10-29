package io.aequicor.heartbeat.feature.aistudio.impl.data

import kotlinx.serialization.json.Json

/** RFC JSON syntax gate: rejects duplicate decoded keys and bounds nesting before building a JSON tree. */
internal class QuestionnaireJsonSyntax(private val text: String) {
    private var position = 0

    fun isValid(): Boolean {
        val isValid = value(0)
        whitespace()
        return isValid && position == text.length
    }

    private fun value(depth: Int): Boolean {
        whitespace()
        if (depth > MAX_DEPTH || position == text.length) return false
        return when (text[position]) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> stringEnd() != null
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> false
        }
    }

    private fun objectValue(depth: Int): Boolean {
        val keys = mutableSetOf<String>()
        return container('}') {
            whitespace()
            val start = position
            val end = stringEnd()
            val key = end?.let { Json.decodeFromString<String>(text.substring(start, it)) }
            whitespace()
            key != null && keys.add(key) && consume(':') && value(depth)
        }
    }

    private fun arrayValue(depth: Int): Boolean = container(']') { value(depth) }

    private inline fun container(close: Char, entry: () -> Boolean): Boolean {
        position++
        whitespace()
        if (consume(close)) return true
        var isValid: Boolean
        do {
            isValid = entry()
            whitespace()
        } while (isValid && consume(','))
        return isValid && consume(close)
    }

    /** Validates escapes and control characters, so decoding a key cannot fail or alias an unchecked key. */
    private fun stringEnd(): Int? {
        if (!consume('"')) return null
        var isValid = true
        var isClosed = false
        while (position < text.length && isValid && !isClosed) {
            val char = text[position++]
            isClosed = char == '"'
            isValid = when (char) {
                '\\' -> escape()
                else -> char >= ' '
            }
        }
        return position.takeIf { isValid && isClosed }
    }

    private fun escape(): Boolean {
        if (position == text.length) return false
        return when (text[position++]) {
            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> true
            'u' -> unicodeEscape()
            else -> false
        }
    }

    private fun unicodeEscape(): Boolean {
        val end = position + UNICODE_DIGITS
        if (end > text.length) return false
        val isValid = text.substring(position, end).all { it in "0123456789abcdefABCDEF" }
        position = end
        return isValid
    }

    private fun number(): Boolean {
        consume('-')
        val hasInteger = consume('0') || (position < text.length && text[position] in '1'..'9' && digits())
        val hasFraction = !consume('.') || digits()
        val hasExponent = !(consume('e') || consume('E')) || exponentDigits()
        return hasInteger && hasFraction && hasExponent
    }

    private fun exponentDigits(): Boolean {
        if (!consume('+')) consume('-')
        return digits()
    }

    private fun digits(): Boolean {
        val start = position
        while (position < text.length && text[position] in '0'..'9') position++
        return position > start
    }

    private fun literal(value: String): Boolean {
        if (!text.startsWith(value, position)) return false
        position += value.length
        return true
    }

    private fun consume(char: Char): Boolean {
        if (position == text.length || text[position] != char) return false
        position++
        return true
    }

    private fun whitespace() {
        while (position < text.length && text[position] in " \r\n\t") position++
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val UNICODE_DIGITS = 4
    }
}
