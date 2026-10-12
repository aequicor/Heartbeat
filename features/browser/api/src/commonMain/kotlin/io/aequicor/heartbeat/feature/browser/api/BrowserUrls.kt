package io.aequicor.heartbeat.feature.browser.api

/**
 * Converts an address to HTTP(S), using HTTPS for a bare hostname (including localhost:port).
 * Credentials, whitespace, control characters, backslashes and ambiguous authorities are rejected.
 * IPv6 literals require an explicit HTTP(S) scheme. Paths, queries and fragments are preserved.
 */
public fun normalizeBrowserUrl(input: String): String? {
    if (input.isEmpty() || input.any(Char::isForbiddenBrowserCharacter)) return null
    val url = withDefaultScheme(input) ?: return null
    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (!validAuthority(authority)) return null
    return url
}

/** Strict navigation-policy check: unlike typed input, callbacks must include an explicit HTTP(S) scheme. */
public fun isBrowserUrlAllowed(url: String): Boolean =
    (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) &&
        normalizeBrowserUrl(url) != null

private const val SPACE_CODE = 32
private const val CONTROL_START = 127
private const val CONTROL_END = 159
private const val MAX_PORT = 65535
private const val MAX_HOST_LENGTH = 253
private const val MAX_LABEL_LENGTH = 63
private const val IPV4_PARTS = 4
private const val MAX_IPV4_PART_LENGTH = 3
private const val MAX_IPV4_PART = 255
private const val IPV6_GROUPS = 8
private const val MAX_IPV6_GROUP_LENGTH = 4
private const val IPV4_GROUPS = 2

private val ReservedSchemes = setOf(
    "http", "https", "javascript", "file", "data", "about", "intent", "ftp", "mailto", "blob",
)

private fun Char.isForbiddenBrowserCharacter(): Boolean =
    isWhitespace() || code < SPACE_CODE || code in CONTROL_START..CONTROL_END || this == '\\'

private fun withDefaultScheme(input: String): String? {
    val separator = input.indexOf("://").takeIf { index ->
        index >= 0 && input.substring(0, index).none { it == '/' || it == '?' || it == '#' }
    } ?: -1
    return if (separator < 0) {
        when {
            input.startsWith("[") || input.startsWith("//") -> null
            ':' in input && input.substringBefore(':').lowercase() in ReservedSchemes -> null
            else -> "https://$input"
        }
    } else {
        val scheme = input.substring(0, separator).lowercase()
        if (scheme == "http" || scheme == "https") scheme + input.substring(separator) else null
    }
}

private fun validAuthority(authority: String): Boolean {
    if (authority.isEmpty() || '@' in authority || '%' in authority) return false
    return if (authority.startsWith("[")) {
        validIpv6Authority(authority)
    } else {
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "").takeIf { ':' in authority }
        validHost(host) && (port == null || validPort(port))
    }
}

private fun validIpv6Authority(authority: String): Boolean {
    val end = authority.indexOf(']')
    if (end < 0 || !validIpv6(authority.substring(1, end))) return false
    val suffix = authority.substring(end + 1)
    return suffix.isEmpty() || (suffix.startsWith(":") && validPort(suffix.drop(1)))
}

private fun validPort(port: String): Boolean =
    port.isNotEmpty() && port.all { it in '0'..'9' } && (port.toIntOrNull() ?: 0) in 1..MAX_PORT

private fun validHost(host: String): Boolean {
    val value = host.removeSuffix(".")
    if (value.isEmpty() || value.length > MAX_HOST_LENGTH) return false
    if (value.all { it in '0'..'9' || it == '.' }) return validIpv4(value)
    return value.split('.').all { label ->
        label.length in 1..MAX_LABEL_LENGTH &&
            label.first().isAsciiLetterOrDigit() &&
            label.last().isAsciiLetterOrDigit() &&
            label.all { it.isAsciiLetterOrDigit() || it == '-' }
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun validIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == IPV4_PARTS && parts.all {
        it.isNotEmpty() && it.length <= MAX_IPV4_PART_LENGTH && (it.length == 1 || it.first() != '0') &&
            it.all { char -> char in '0'..'9' } && (it.toIntOrNull() ?: -1) in 0..MAX_IPV4_PART
    }
}

private fun validIpv6(value: String): Boolean {
    if (value.isEmpty() || ":::" in value) return false
    val compression = value.indexOf("::")
    if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) return false
    val parts = ipv6Parts(value, compression)
    val groups = parts.mapIndexed { index, part ->
        ipv6GroupSize(part, isLast = index == parts.lastIndex && value.endsWith(part))
    }
    if (0 in groups) return false
    return if (compression >= 0) groups.sum() < IPV6_GROUPS else groups.sum() == IPV6_GROUPS
}

private fun ipv6Parts(value: String, compression: Int): List<String> = if (compression >= 0) {
    val left = value.substring(0, compression)
    val right = value.substring(compression + 2)
    (if (left.isEmpty()) emptyList() else left.split(':')) +
        (if (right.isEmpty()) emptyList() else right.split(':'))
} else {
    value.split(':')
}

private fun ipv6GroupSize(part: String, isLast: Boolean): Int = when {
    '.' in part -> if (isLast && validIpv4(part)) IPV4_GROUPS else 0
    part.length in 1..MAX_IPV6_GROUP_LENGTH && part.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } -> 1
    else -> 0
}
