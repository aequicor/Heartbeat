package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** Validated preview input or a concise diagnostic that never echoes HTML or sample data. */
internal sealed interface UiPreviewArguments {
    data class Valid(val request: BrowserDocumentPreviewRequest, val isScreenshotIncluded: Boolean) : UiPreviewArguments
    data class Invalid(val message: String) : UiPreviewArguments
}

/** Checks raw tool arguments before a renderer or owner can be reached. */
internal fun previewArguments(arguments: JsonObject): UiPreviewArguments {
    if (arguments.keys.any { it !in PREVIEW_KEYS }) {
        return invalid("Only html, data, width, height, theme, fontScale and includeScreenshot are supported")
    }
    val html = arguments.string("html")?.takeIf { it.isNotBlank() }
        ?: return invalid("html must be a nonblank string")
    if (html.encodeToByteArray().size > UiSpecLimits.HTML_BYTES) {
        return invalid("html exceeds the web node limit of ${UiSpecLimits.HTML_BYTES} UTF-8 bytes")
    }
    val width = arguments.integer("width", 900)
    if (!width.isWithin(BrowserDocumentPreviewLimits.MIN_WIDTH..BrowserDocumentPreviewLimits.MAX_WIDTH)) {
        return invalid(
            "width must be an integer from ${BrowserDocumentPreviewLimits.MIN_WIDTH} " +
                "to ${BrowserDocumentPreviewLimits.MAX_WIDTH}",
        )
    }
    val height = arguments.integer("height", 640)
    if (!height.isWithin(BrowserDocumentPreviewLimits.MIN_HEIGHT..BrowserDocumentPreviewLimits.MAX_HEIGHT)) {
        return invalid(
            "height must be an integer from ${BrowserDocumentPreviewLimits.MIN_HEIGHT} " +
                "to ${BrowserDocumentPreviewLimits.MAX_HEIGHT}",
        )
    }
    val theme = if ("theme" in arguments) arguments.string("theme") else "light"
    if (theme != "light" && theme != "dark") return invalid("theme must be light or dark")
    val isScreenshotIncluded = arguments.boolean("includeScreenshot", false)
        ?: return invalid("includeScreenshot must be a boolean")
    val scale = if ("fontScale" in arguments) arguments.primitive("fontScale")?.floatOrNull else 1f
    if (!scale.isPreviewScale()) {
        return invalid(
            "fontScale must be a finite number from ${BrowserDocumentPreviewLimits.MIN_FONT_SCALE} " +
                "to ${BrowserDocumentPreviewLimits.MAX_FONT_SCALE}",
        )
    }
    val data = arguments["data"]
    if (!data.isPreviewData()) {
        return invalid("data exceeds the document depth or byte limit")
    }
    return UiPreviewArguments.Valid(
        BrowserDocumentPreviewRequest(
            html,
            data,
            checkNotNull(width),
            checkNotNull(height),
            theme == "dark",
            checkNotNull(scale),
        ),
        isScreenshotIncluded,
    )
}

private fun invalid(message: String): UiPreviewArguments.Invalid = UiPreviewArguments.Invalid(message)

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.primitive(name: String): JsonPrimitive? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }

private fun JsonObject.integer(name: String, default: Int): Int? =
    if (name in this) primitive(name)?.intOrNull else default

private fun JsonObject.boolean(name: String, default: Boolean): Boolean? =
    if (name in this) primitive(name)?.booleanOrNull else default

private fun Int?.isWithin(range: IntRange): Boolean = this != null && this in range

private fun Float?.isPreviewScale(): Boolean = this != null && isFinite() &&
    this in BrowserDocumentPreviewLimits.MIN_FONT_SCALE..BrowserDocumentPreviewLimits.MAX_FONT_SCALE

private fun JsonElement?.isPreviewData(): Boolean = this == null ||
    (hasPreviewDepth() && toString().encodeToByteArray().size <= BrowserDocumentLimits.DATA_BYTES)

/** Stops before serialization recurses through an excessively nested sample. */
private fun JsonElement.hasPreviewDepth(depth: Int = 0): Boolean = when {
    depth > BrowserDocumentLimits.JSON_DEPTH -> false
    this is JsonObject -> values.all { it.hasPreviewDepth(depth + 1) }
    this is JsonArray -> all { it.hasPreviewDepth(depth + 1) }
    else -> true
}

private val PREVIEW_KEYS = setOf("html", "data", "width", "height", "theme", "fontScale", "includeScreenshot")
