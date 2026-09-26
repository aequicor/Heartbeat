package io.aequicor.heartbeat.core.navigation.impl

import io.aequicor.heartbeat.core.navigation.DeepLinkConfig
import io.aequicor.heartbeat.core.navigation.DeepLinkEntry
import io.aequicor.heartbeat.core.navigation.DeepLinkParams

/**
 * Parses deep links (`scheme://a/b?x=1`, `https://<web host>/a/b`) and matches them against [DeepLinkEntry]
 * patterns. Pure: no navigation, no logging of the link itself (it may carry personal data).
 */
internal class DeepLinkRouter(private val config: DeepLinkConfig, entries: Collection<DeepLinkEntry>) {

    private val patterns: List<Pattern> = entries.map(::Pattern).sortedByDescending { it.literals }

    init {
        patterns.groupBy { it.shape }.forEach { (shape, same) ->
            require(same.size == 1) { "duplicate deep link pattern $shape: ${same.joinToString { it.entry.pattern }}" }
        }
    }

    sealed interface Resolution {
        class Rejected(val reason: String) : Resolution

        data object NoMatch : Resolution

        class Matched(val entry: DeepLinkEntry, val params: DeepLinkParams) : Resolution
    }

    fun resolve(uri: String): Resolution {
        val parsed = parse(uri) ?: return Resolution.Rejected("malformed link or foreign origin")
        for (pattern in patterns) {
            val path = pattern.match(parsed.segments) ?: continue
            return Resolution.Matched(pattern.entry, DeepLinkParams(path, parsed.query))
        }
        return Resolution.NoMatch
    }

    private data class Link(val segments: List<String>, val query: Map<String, String>)

    private fun parse(uri: String): Link? {
        val schemeEnd = uri.indexOf(SCHEME_SEPARATOR)
        if (schemeEnd <= 0) return null
        val scheme = uri.substring(0, schemeEnd).lowercase()
        val rest = uri.substring(schemeEnd + SCHEME_SEPARATOR.length).substringBefore('#')
        val raw = rest.substringBefore('?').split('/').filter { it.isNotEmpty() }
        val segments = when {
            scheme in config.schemes -> raw
            scheme == HTTPS && raw.firstOrNull()?.lowercase() in config.webHosts -> raw.drop(1)
            else -> return null
        }.map { percentDecode(it, plusIsSpace = false) ?: return null }
        val query = rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
            val name = percentDecode(pair.substringBefore('='), plusIsSpace = true) ?: return null
            val value = percentDecode(pair.substringAfter('=', ""), plusIsSpace = true) ?: return null
            name to value
        }
        return Link(segments, query)
    }

    private class Pattern(val entry: DeepLinkEntry) {
        private val parts = entry.pattern.split('/').filter { it.isNotEmpty() }

        init {
            require(parts.isNotEmpty()) { "empty deep link pattern" }
        }

        val literals = parts.count { !it.isPlaceholder() }

        /** Pattern with placeholder names erased: two patterns of the same shape are ambiguous. */
        val shape = parts.joinToString("/") { if (it.isPlaceholder()) "{}" else it }

        fun match(segments: List<String>): Map<String, String>? {
            if (segments.size != parts.size) return null
            val params = mutableMapOf<String, String>()
            parts.zip(segments).forEach { (part, segment) ->
                when {
                    part.isPlaceholder() -> params[part.substring(1, part.length - 1)] = segment
                    part != segment -> return null
                }
            }
            return params
        }

        private fun String.isPlaceholder() = length > 2 && startsWith('{') && endsWith('}')
    }

    private companion object {
        const val SCHEME_SEPARATOR = "://"
        const val HTTPS = "https"
    }
}

/**
 * Decodes `%XX` sequences as UTF-8 (and `+` as space in queries); `null` for a malformed sequence.
 * Works on UTF-8 bytes: `%`, `+` and hex digits are ASCII, every other character keeps its own bytes.
 */
internal fun percentDecode(value: String, plusIsSpace: Boolean): String? {
    val input = value.encodeToByteArray()
    val output = ByteArray(input.size)
    var size = 0
    var i = 0
    while (i < input.size) {
        val byte = input[i]
        output[size++] = when {
            byte == PERCENT -> hexByte(input, i + 1) ?: return null
            byte == PLUS && plusIsSpace -> SPACE
            else -> byte
        }
        i += if (byte == PERCENT) PERCENT_SEQUENCE_LENGTH else 1
    }
    return output.decodeToString(0, size)
}

private fun hexByte(input: ByteArray, at: Int): Byte? {
    val high = input.getOrNull(at)?.hexDigit()
    val low = input.getOrNull(at + 1)?.hexDigit()
    return if (high == null || low == null) null else (high * HEX_RADIX + low).toByte()
}

// ASCII only: digitToIntOrNull would also accept non-ASCII Unicode digits
private fun Byte.hexDigit(): Int? {
    val c = toInt().toChar()
    return if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') c.digitToInt(HEX_RADIX) else null
}

private const val HEX_RADIX = 16
private const val PERCENT_SEQUENCE_LENGTH = 3
private const val PERCENT = '%'.code.toByte()
private const val PLUS = '+'.code.toByte()
private const val SPACE = ' '.code.toByte()
