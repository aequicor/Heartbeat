package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.feature.browser.api.normalizeBrowserUrl
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Approval texts shared by the browser tools: what is typed, pressed or clicked, shown exactly as it reaches the
 * page. Control, format (bidi overrides and isolates, zero-width, tags), line/paragraph separator and lone surrogate
 * characters are shown escaped, so an approval cannot be visually rearranged.
 */
internal object BrowserApprovalText {
    const val MAX_APPROVAL_CHARS = 500
    const val MAX_SHORT_CHARS = 40
    private const val TRUNCATED = "… (показано начало)"
    private const val HEX_RADIX = 16
    private const val UNICODE_DIGITS = 4
    private val HIDDEN_TYPES = setOf(
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.SURROGATE.toInt(),
    )

    /** [value] with hidden characters escaped; a longer value keeps its first [limit] characters whole. */
    fun visible(value: String?, limit: Int = MAX_APPROVAL_CHARS): String {
        val text = value.orEmpty()
        val shown = StringBuilder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val piece = when {
                codePoint == '\n'.code -> "\\n"
                codePoint == '\t'.code -> "\\t"
                Character.isISOControl(codePoint) || Character.getType(codePoint) in HIDDEN_TYPES -> escape(codePoint)
                else -> String(Character.toChars(codePoint))
            }
            if (shown.length + piece.length > limit) return shown.append(TRUNCATED).toString()
            shown.append(piece)
            index += Character.charCount(codePoint)
        }
        return shown.toString()
    }

    /** Typed text with its length. */
    fun describeText(text: String?): String {
        val value = text.orEmpty()
        return "Текст (${value.length} симв.): ${visible(value)}"
    }

    /** A point of the [x] and [y] arguments. */
    fun point(arguments: JsonObject, x: String, y: String): String =
        "${visible(arguments.text(x) ?: "?", MAX_SHORT_CHARS)}, ${visible(arguments.text(y) ?: "?", MAX_SHORT_CHARS)}"

    /**
     * A click shown as the owner's read of that snapshot reported the element ([element] is that read's entry, if
     * known), or as a point; no IO happens here.
     */
    fun describeClick(arguments: JsonObject, element: ReadElement?): String {
        val reference = arguments.text("element")
            ?: return "Точка ${point(arguments, "x", "y")} (CSS-пиксели)"
        val shown = "Элемент №${visible(reference, MAX_SHORT_CHARS)} из последнего чтения страницы"
        element ?: return "$shown (описание недоступно)"
        val link = element.href?.let { ", ссылка: ${visible(it)}" }.orEmpty()
        return "$shown: ${visible(element.tag, MAX_SHORT_CHARS)} «${visible(element.label)}»$link"
    }

    private fun escape(codePoint: Int): String {
        val hex = codePoint.toString(HEX_RADIX).padStart(UNICODE_DIGITS, '0')
        return if (Character.isBmpCodePoint(codePoint)) "\\u$hex" else "\\u{$hex}"
    }
}

/** A string argument, `null` when absent or JSON null. */
internal fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/**
 * The address [address] opens when it is not a public web URL, else null. It is normalized like the browser does
 * (a bare host gets https://), so the check sees what the tab opens; an invalid address needs no decision.
 */
internal fun nonPublicAddress(address: String?): String? {
    val url = normalizeBrowserUrl(address ?: return null) ?: return null
    return url.takeUnless(::isPublicWebUrl)
}

/** An element as the agent's read reported it. */
internal data class ReadElement(val tag: String, val label: String, val href: String?)

/**
 * The latest read of each session, so one session's reads never displace another's; an older snapshot only loses
 * its approval text. Bounded in sessions, elements and text; reads of closed tabs are dropped by [forgetExcept].
 */
internal class BrowserReadMemory {
    private val reads = object : LinkedHashMap<String, LatestRead>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LatestRead>?): Boolean =
            size > MAX_REMEMBERED_SESSIONS
    }

    /** Remembers [owner]'s read of [tabId]; texts are kept only as far as an approval shows them. */
    fun remember(owner: String, tabId: String, data: JsonObject) {
        val snapshot = data.text("snapshotId")?.takeIf { it.length <= BrowserApprovalText.MAX_SHORT_CHARS } ?: return
        val elements = (data["elements"] as? JsonArray).orEmpty().asSequence().mapNotNull { element ->
            val fields = element as? JsonObject ?: return@mapNotNull null
            val id = (fields["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            id to ReadElement(
                fields.text("tag").orEmpty().take(BrowserApprovalText.MAX_SHORT_CHARS + 1),
                fields.text("label").orEmpty().take(BrowserApprovalText.MAX_APPROVAL_CHARS + 1),
                fields.text("href")?.take(BrowserApprovalText.MAX_APPROVAL_CHARS + 1),
            )
        }.take(MAX_REMEMBERED_ELEMENTS).toMap()
        synchronized(reads) {
            reads.remove(owner)
            reads[owner] = LatestRead(tabId, snapshot, elements)
        }
    }

    /** The element [id] of [owner]'s latest read, if it was [snapshot]. */
    fun remembered(owner: String, snapshot: String?, id: Int?): ReadElement? {
        if (snapshot == null || id == null) return null
        return synchronized(reads) { reads[owner]?.takeIf { it.snapshot == snapshot }?.elements?.get(id) }
    }

    /** Drops reads of tabs other than [open]. */
    fun forgetExcept(open: Set<String>) {
        synchronized(reads) { reads.values.removeAll { it.tabId !in open } }
    }

    private data class LatestRead(val tabId: String, val snapshot: String, val elements: Map<Int, ReadElement>)

    private companion object {
        const val MAX_REMEMBERED_SESSIONS = 32
        const val MAX_REMEMBERED_ELEMENTS = 200
    }
}

/** Parameters of every browser tool. */
internal const val BROWSER_TOOL_SCHEMA =
    """{"type":"object","properties":{
            "tabId":{"type":"string"},"url":{"type":"string"},"query":{"type":"string"},
            "snapshotId":{"type":"string"},"element":{"type":"integer","minimum":0,"maximum":199},
            "x":{"type":"number"},"y":{"type":"number"},"toX":{"type":"number"},"toY":{"type":"number"},
            "deltaY":{"type":"number"},"text":{"type":"string","maxLength":20000},"key":{"type":"string"}
        },"additionalProperties":false}"""
