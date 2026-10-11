package io.aequicor.heartbeat.feature.browser.api

/**
 * Address-bar input accepts a URL or a Google query; explicit invalid schemes are never repaired.
 */
public fun browserAddress(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.code < FIRST_PRINTABLE_CODE }) return null
    if ("://" in text || ':' in text || text.startsWith("//")) return normalizeBrowserUrl(text)
    val host = text.substringBefore('/').substringBefore('?').substringBefore('#')
    return if ('.' in host || host == "localhost") {
        normalizeBrowserUrl(text)
    } else {
        browserSearchUrl(text)
    }
}

/** UTF-8 percent encoding prevents queries from changing the destination or URL structure. */
public fun browserSearchUrl(query: String): String = "https://www.google.com/search?q=" +
    buildString {
        query.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and BYTE_MASK
            val char = value.toChar()
            if (char.isAsciiUnreserved()) {
                append(char)
            } else {
                append('%')
                append(value.toString(HEX_RADIX).uppercase().padStart(2, '0'))
            }
        }
    }

private fun Char.isAsciiUnreserved(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || isAsciiDigitOrSymbol()

private fun Char.isAsciiDigitOrSymbol(): Boolean = this in '0'..'9' || this in "-._~"

private const val FIRST_PRINTABLE_CODE = 32
private const val BYTE_MASK = 255
private const val HEX_RADIX = 16
