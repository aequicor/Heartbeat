package io.aequicor.heartbeat.feature.browser.api

/**
 * Address-bar input accepts a URL or a Google query; explicit invalid schemes are never repaired.
 * Without an explicit scheme, text with whitespace or text that is not a valid address becomes a
 * query (`kotlin 2.0 release`, `what is 12:30`), while hosts such as `example.com` and
 * `localhost:8080` stay addresses.
 */
public fun browserAddress(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.code < FIRST_PRINTABLE_CODE }) return null
    if (text.hasExplicitScheme()) return normalizeBrowserUrl(text)
    if (text.any(Char::isWhitespace)) return browserSearchUrl(text)
    val host = text.substringBefore('/').substringBefore('?').substringBefore('#')
    val address = if ('.' in host || ':' in host || host == "localhost") normalizeBrowserUrl(text) else null
    return address ?: browserSearchUrl(text)
}

/**
 * `scheme://`, `//authority` or `scheme:value` (RFC 3986 scheme syntax) where the value is not a port,
 * so `javascript:alert(1)` keeps its scheme while `localhost:8080/path` is a host with a port.
 */
private fun String.hasExplicitScheme(): Boolean {
    if ("://" in this || startsWith("//")) return true
    val colon = indexOf(':')
    if (colon <= 0) return false
    val scheme = substring(0, colon)
    val isScheme = scheme.first().isAsciiLetter() &&
        scheme.all { it.isAsciiLetter() || it in '0'..'9' || it in "+-." }
    val value = substring(colon + 1)
    if (!isScheme || value.firstOrNull()?.isWhitespace() == true) return false
    val port = value.takeWhile { it !in "/?#" }
    return port.isEmpty() || port.any { it !in '0'..'9' }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

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
