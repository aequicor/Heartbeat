package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What an independent safety review of a browser action stays bound to: the tab, its document and address, the
 * viewport, and the target, hit target, destination and focus with their form. The reviewer also sees the page's
 * title and text, but those are left out here: a live page rewrites them (clocks, counters, rotating banners) while a
 * review runs, and the action is defined by the element it reaches, not by the text around it. The same holds for
 * the label and the size of the page element itself (the document's body and root, flagged `isPage` by the evidence
 * script, not by a tag a page could give any element): focus rests on it until something is focused and a click can
 * land on it, and its label is the page's text.
 *
 * Residual risk: a page can still rewrite its text between the review and the action as long as the reached element,
 * its label and its form stay the same; the reviewer judges the element and its effect, not the page text.
 */
internal fun reviewedBrowserState(evidence: JsonObject): JsonObject {
    val page = (evidence["page"] as? JsonObject)?.let(::stablePage)
    return JsonObject(evidence - VOLATILE_TAB_FIELDS + ("page" to (page ?: JsonObject(emptyMap()))))
}

private fun stablePage(page: JsonObject): JsonObject = JsonObject(
    (page - VOLATILE_PAGE_FIELDS).mapValues { (key, value) ->
        if (key in ELEMENT_FIELDS) stableElement(value) else value
    },
)

private fun stableElement(element: JsonElement): JsonElement {
    if (element !is JsonObject) return element
    val isPage = (element["isPage"] as? JsonPrimitive)?.booleanOrNull == true
    return if (isPage) JsonObject(element - VOLATILE_PAGE_ELEMENT_FIELDS) else element
}

private val VOLATILE_TAB_FIELDS = setOf("title")
private val VOLATILE_PAGE_FIELDS = setOf("title", "content", "isContentTruncated")
private val ELEMENT_FIELDS = setOf("target", "hitTarget", "focus", "destination")
private val VOLATILE_PAGE_ELEMENT_FIELDS = setOf("label", "rect")
