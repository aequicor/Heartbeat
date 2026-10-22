package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable

/**
 * What a Markdown link points at, decided from its destination alone, without IO. Unknown schemes (`javascript:`,
 * `data:`, …) have no target and stay inert text. [destination] is the normalized value a link handler receives.
 */
@Immutable
public sealed interface HbLinkTarget {
    public val destination: String

    /** An `http`/`https` page; a bare `www.` host gains `https://`. */
    public data class Web(override val destination: String) : HbLinkTarget

    /** A `mailto:` address. */
    public data class Mail(override val destination: String) : HbLinkTarget

    /** A `tel:` number. */
    public data class Phone(override val destination: String) : HbLinkTarget

    /**
     * A local file: a `file:` URI, an absolute path, a `~/` path or a path relative to the conversation's project.
     * [path] is decoded and stripped of the line suffix (`:12`, `:12:5`) or fragment (`#L12`, `#L12-L20`);
     * [line] is 1-based. Resolving and reading the file is the caller's job.
     */
    public data class File(override val destination: String, val path: String, val line: Int? = null) :
        HbLinkTarget

    /** A `#fragment` inside the same document. */
    public data class Anchor(override val destination: String, val fragment: String) : HbLinkTarget
}

/** Parses a Markdown link destination; null for blank, oversized, control-character or unsupported ones. */
public fun parseHbLink(destination: String): HbLinkTarget? {
    val value = destination.trim().removeSurrounding("<", ">").trim()
    if (value.isEmpty() || value.length > MAX_LINK_LENGTH || value.any { it.isISOControl() }) return null
    if (value.startsWith('#')) return value.drop(1).takeIf { it.isNotBlank() }?.let { HbLinkTarget.Anchor(value, it) }
    val scheme = LinkScheme.find(value)?.groupValues?.get(1)?.lowercase()
    // A single letter before ':' is a Windows drive; `Main.kt:42` is a line, not a scheme.
    return if (scheme == null || scheme.length == 1 || LineSuffix.matches(value.substring(scheme.length))) {
        pathLink(value)
    } else {
        schemeLink(value, scheme)
    }
}

private fun schemeLink(value: String, scheme: String): HbLinkTarget? {
    val rest = value.substring(scheme.length + 1)
    return when (scheme) {
        "http", "https" -> HbLinkTarget.Web(value).takeIf { rest.removePrefix("//").isNotBlank() }
        "mailto" -> HbLinkTarget.Mail(value).takeIf { rest.isNotBlank() }
        "tel" -> HbLinkTarget.Phone(value).takeIf { rest.isNotBlank() }
        "file" -> fileUriLink(value)
        else -> null
    }
}

/** A GFM autolink is a URL, a `www.` host or a bare e-mail address; only the first carries its own scheme. */
internal fun autolinkDestination(value: String): String = when {
    LinkScheme.containsMatchIn(value) -> value
    value.startsWith("www.", ignoreCase = true) -> "https://$value"
    '@' in value && '/' !in value -> "mailto:$value"
    else -> value
}

private fun pathLink(value: String): HbLinkTarget? {
    if (value.startsWith("//") || value.startsWith("\\\\")) return null
    if (value.startsWith("www.", ignoreCase = true)) return HbLinkTarget.Web("https://$value")
    return fileLink(value, decode(value.substringBefore('?')))
}

private fun fileUriLink(value: String): HbLinkTarget? {
    var rest = value.substring("file:".length)
    if (rest.startsWith("//")) {
        rest = rest.removePrefix("//")
        val host = rest.substringBefore('/')
        if (host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) return null
        rest = rest.substring(host.length)
    }
    // file:///C:/dir keeps the drive without the leading slash.
    if (WindowsDrivePath.matches(rest)) rest = rest.drop(1)
    return fileLink(value, decode(rest.substringBefore('?')))
}

private fun fileLink(destination: String, raw: String): HbLinkTarget.File? {
    val fragment = raw.substringAfter('#', "")
    var path = raw.substringBefore('#')
    var line = LineFragment.matchEntire(fragment)?.groupValues?.get(1)?.toIntOrNull()
    if (line == null) {
        LineSuffix.find(path)?.let { match ->
            line = match.groupValues[1].toIntOrNull()
            path = path.substring(0, match.range.first)
        }
    }
    val start = line?.takeIf { number -> number > 0 }
    return path.takeIf { it.isNotBlank() }?.let { HbLinkTarget.File(destination, it, start) }
}

/** Percent-decodes UTF-8 escapes; a malformed escape leaves the text as written. */
private fun decode(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var plainStart = 0
    var index = 0
    while (index < value.length) {
        val hex = if (value[index] == '%' && index + PERCENT_ESCAPE_LENGTH <= value.length) {
            value.substring(index + 1, index + PERCENT_ESCAPE_LENGTH).takeIf { it.all(Char::isLetterOrDigit) }
                ?.toIntOrNull(HEX_RADIX)
        } else {
            null
        }
        if (hex == null) {
            index++
            continue
        }
        bytes += value.substring(plainStart, index).encodeToByteArray().asList()
        bytes += hex.toByte()
        index += PERCENT_ESCAPE_LENGTH
        plainStart = index
    }
    bytes += value.substring(plainStart).encodeToByteArray().asList()
    return bytes.toByteArray().decodeToString()
}

private const val MAX_LINK_LENGTH = 4096
private const val PERCENT_ESCAPE_LENGTH = 3
private const val HEX_RADIX = 16
private val LinkScheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
private val WindowsDrivePath = Regex("^/[a-zA-Z]:([/\\\\].*)?$")
private val LineFragment = Regex("^L(\\d{1,7})(?:-L?\\d{1,7})?$")
private val LineSuffix = Regex(":(\\d{1,7})(?::\\d{1,7})?$")
