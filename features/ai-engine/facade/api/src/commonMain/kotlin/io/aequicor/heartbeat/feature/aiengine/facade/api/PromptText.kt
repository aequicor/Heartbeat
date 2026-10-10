package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** [text] cut to [max] characters with a visible mark. */
public fun cut(text: String, max: Int): String =
    if (text.length <= max) text else text.take((max - CUT_MARK.length).coerceAtLeast(0)) + CUT_MARK

/**
 * The marker around text other sessions wrote, in one prompt. Its nonce is fresh for every prompt and never left in
 * the text it wraps, so nothing a cell, user or judge wrote can close the fence and go on as the host, however it
 * spells or disguises a marker.
 */
public class Fence(private val nonce: String) {
    init {
        require(nonce.isNotEmpty() && nonce.all(Char::isLetterOrDigit)) { "A fence nonce is letters and digits" }
    }

    /** The line that opens fenced text. */
    public val open: String = "<<<$nonce"

    /** The line that closes it. */
    public val close: String = "$nonce>>>"

    /** How a reader tells fenced text apart. */
    public val lines: String = "between a line \"$open\" and a line \"$close\""

    /** [text] between [open] and [close]. */
    public fun wrap(text: String): String = "$open\n${text.without(nonce)}\n$close"

    /** Creates fresh delimiters for each host prompt. */
    public companion object {
        /** A fence no earlier text could know. */
        @OptIn(ExperimentalUuidApi::class)
        public fun random(): Fence = Fence(Uuid.random().toHexString().take(NONCE_CHARS))
    }
}

private tailrec fun String.without(nonce: String): String =
    if (nonce in this) replace(nonce, "").without(nonce) else this

/**
 * [text] of another session as one short line, for one-line descriptions: line breaks, control and invisible
 * format characters (also those outside the basic plane, such as tag characters) become spaces, so it cannot break
 * the line it is shown in.
 */
public fun brief(text: String): String {
    val line = spacedOut(text).split(' ').filter(String::isNotEmpty).joinToString(" ")
    return if (line.length <= BRIEF_CHARS) line else line.cutBefore(BRIEF_CHARS - 1) + "…"
}

private fun spacedOut(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        val width = if (isPairAt(text, index)) 2 else 1
        append(if (isBreakingAt(text, index, width)) " " else text.substring(index, index + width))
        index += width
    }
}

private fun isPairAt(text: String, index: Int): Boolean =
    text[index].isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true

private fun isBreakingAt(text: String, index: Int, width: Int): Boolean {
    if (width == 1) return text[index].isBreaking()
    val code = (text[index].code - HIGH_SURROGATES shl SURROGATE_BITS) + (text[index + 1].code - LOW_SURROGATES)
    return ASTRAL_FORMATS.any { code + ASTRAL_START in it }
}

private fun Char.isBreaking(): Boolean =
    isWhitespace() || isISOControl() || isSurrogate() || category == CharCategory.FORMAT

/** The first [length] characters, one fewer when the cut would leave half a surrogate pair. */
private fun String.cutBefore(length: Int): String {
    val kept = take(length)
    return if (kept.last().isHighSurrogate()) kept.dropLast(1) else kept
}

private const val CUT_MARK = "\n…[cut]"
private const val BRIEF_CHARS = 200
private const val NONCE_CHARS = 12
private const val HIGH_SURROGATES = 0xD800
private const val LOW_SURROGATES = 0xDC00
private const val ASTRAL_START = 0x10000

/** Invisible format characters outside the basic plane: shorthand format controls, musical format and tags. */
private val ASTRAL_FORMATS = listOf(0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE007F)

/** [text] on one line: every run of whitespace, line breaks included, becomes a single space. */
public fun singleLine(text: String): String = buildString {
    var isSpace = false
    for (char in text.trim()) {
        if (char.isWhitespace()) {
            if (!isSpace) append(' ')
        } else {
            append(char)
        }
        isSpace = char.isWhitespace()
    }
}

/**
 * Whether [text] carries characters a reviewer cannot see: control characters other than line breaks and tabs,
 * format characters (bidi overrides, zero-width marks) and Unicode tag characters (U+E0000..U+E007F).
 */
public fun hasHiddenCharacters(text: String): Boolean = text.indices.any { index ->
    val char = text[index]
    val isControl = char.isISOControl() && char != '\n' && char != '\t' && char != '\r'
    isControl || char.category == CharCategory.FORMAT || text.isTagCharacterAt(index)
}

private fun String.isTagCharacterAt(index: Int): Boolean {
    if (!this[index].isHighSurrogate() || index + 1 >= length) return false
    val code = SUPPLEMENTARY_BASE + ((this[index].code - HIGH_SURROGATE_BASE) shl SURROGATE_BITS) +
        (this[index + 1].code - LOW_SURROGATE_BASE)
    return code in TAG_CHARACTERS
}

private const val SUPPLEMENTARY_BASE = 0x10000
private const val HIGH_SURROGATE_BASE = 0xD800
private const val LOW_SURROGATE_BASE = 0xDC00
private const val SURROGATE_BITS = 10
private val TAG_CHARACTERS = 0xE0000..0xE007F

/** Common credential shapes: provider keys, tokens, private keys and `key = value` assignments. */
public fun looksLikeSecret(text: String): Boolean = SECRET_PATTERNS.any { it.containsMatchIn(text) }

private val SECRET_PATTERNS = listOf(
    Regex("""\bsk-[A-Za-z0-9_-]{20,}"""),
    Regex("""\b(?:ghp|gho|ghu|ghs|github_pat)_[A-Za-z0-9_]{20,}"""),
    Regex("""\bAKIA[0-9A-Z]{16}\b"""),
    Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""),
    Regex("""\bxox[abposr]-[A-Za-z0-9-]{10,}"""),
    Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
    Regex("""(?i)\b(?:api[_-]?key|access[_-]?token|secret|password|passwd)\b\s*[:=]\s*["']?[^\s"']{8,}"""),
)

/** A per-prompt character budget for complete lines, including one separator per line. */
public class PromptBudget(private var remaining: Int) {
    /** Number of lines that did not fit. */
    public var omitted: Int = 0
        private set

    /** Takes a whole line plus its separator, or records that it did not fit. */
    public fun take(line: String): String? = if (line.length + 1 <= remaining) {
        remaining -= line.length + 1
        line
    } else {
        omitted++
        null
    }
}
