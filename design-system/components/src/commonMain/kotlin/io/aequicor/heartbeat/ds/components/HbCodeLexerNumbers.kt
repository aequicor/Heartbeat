package io.aequicor.heartbeat.ds.components

internal fun String.isHbCodeNumberStart(index: Int, language: HbCodeLanguage): Boolean {
    if (this[index] in DECIMAL_DIGITS) return true
    if (this[index] != '.' || language == HbCodeLanguage.Json || language == HbCodeLanguage.Kotlin) return false
    return getOrNull(index + 1) in DECIMAL_DIGITS && getOrNull(index - 1) != '.'
}

internal fun String.hbCodeNumberEnd(start: Int, language: HbCodeLanguage): Int {
    val radixDigits = if (this[start] == '0' && language != HbCodeLanguage.Json) {
        when (getOrNull(start + 1)?.lowercaseChar()) {
            'x' -> HEXADECIMAL_DIGITS
            'b' -> BINARY_DIGITS
            'o' -> OCTAL_DIGITS
            else -> null
        }
    } else {
        null
    }
    val end = if (radixDigits == null) {
        decimalEnd(start)
    } else {
        digitEnd(start + RADIX_PREFIX_LENGTH, radixDigits)
    }
    var suffixEnd = end
    while (getOrNull(suffixEnd) in language.numberSuffixes) suffixEnd++
    return suffixEnd
}

private fun String.decimalEnd(start: Int): Int {
    var end = digitEnd(start, DECIMAL_DIGITS)
    if (getOrNull(end) == '.' && getOrNull(end + 1) in DECIMAL_DIGITS) end = digitEnd(end + 1, DECIMAL_DIGITS)
    if (getOrNull(end) != 'e' && getOrNull(end) != 'E') return end
    var exponentStart = end + 1
    if (getOrNull(exponentStart) == '+' || getOrNull(exponentStart) == '-') exponentStart++
    return if (getOrNull(exponentStart) in DECIMAL_DIGITS) digitEnd(exponentStart, DECIMAL_DIGITS) else end
}

private fun String.digitEnd(start: Int, digits: String): Int {
    var end = start
    while (getOrNull(end) in digits || getOrNull(end) == '_') end++
    return end
}

private operator fun String.contains(character: Char?): Boolean = character != null && indexOf(character) >= 0

private const val RADIX_PREFIX_LENGTH = 2
private const val DECIMAL_DIGITS = "0123456789"
private const val HEXADECIMAL_DIGITS = "0123456789abcdefABCDEF"
private const val BINARY_DIGITS = "01"
private const val OCTAL_DIGITS = "01234567"
