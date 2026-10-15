package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Validate before preparing native resources or opening any document, including direct non-tool callers. */
internal fun BrowserDocumentPreviewRequest.isValidPreview(): Boolean = html.isNotBlank() &&
    width in BrowserDocumentPreviewLimits.MIN_WIDTH..BrowserDocumentPreviewLimits.MAX_WIDTH &&
    height in BrowserDocumentPreviewLimits.MIN_HEIGHT..BrowserDocumentPreviewLimits.MAX_HEIGHT &&
    fontScale in BrowserDocumentPreviewLimits.MIN_FONT_SCALE..BrowserDocumentPreviewLimits.MAX_FONT_SCALE &&
    html.encodeToByteArray().size <= BrowserDocumentLimits.HTML_BYTES &&
    data.isShallowPreviewData() &&
    (data?.toString()?.encodeToByteArray()?.size ?: 0) <= BrowserDocumentLimits.DATA_BYTES

/** Iterative admission keeps recursive JSON serialization from exhausting the stack of a direct API caller. */
private fun JsonElement?.isShallowPreviewData(): Boolean {
    if (this == null) return true
    val pending = ArrayDeque<Pair<JsonElement, Int>>()
    pending.addLast(this to 0)
    var visited = 0
    while (pending.isNotEmpty()) {
        val (element, depth) = pending.removeLast()
        if (depth > BrowserDocumentLimits.JSON_DEPTH || ++visited > BrowserDocumentLimits.DATA_BYTES) return false
        val children = when (element) {
            is JsonObject -> element.values
            is JsonArray -> element
            is JsonPrimitive -> emptyList()
        }
        children.forEach { pending.addLast(it to depth + 1) }
    }
    return true
}
