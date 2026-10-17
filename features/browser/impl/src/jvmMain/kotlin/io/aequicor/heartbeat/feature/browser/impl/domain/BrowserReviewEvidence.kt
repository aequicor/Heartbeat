package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlinx.serialization.json.JsonObject

/**
 * What an independent safety review of a browser action stays bound to: the tab, its document and address, the
 * viewport, and the target, hit target, destination and focus with their form. The reviewer also sees the page's
 * title and text, but those are left out here: a live page rewrites them (clocks, counters, rotating banners) while a
 * review runs, and the action is defined by the element it reaches, not by the text around it.
 */
internal fun reviewedBrowserState(evidence: JsonObject): JsonObject {
    val page = (evidence["page"] as? JsonObject)?.let { JsonObject(it - VOLATILE_PAGE_FIELDS) }
    return JsonObject(evidence - VOLATILE_TAB_FIELDS + ("page" to (page ?: JsonObject(emptyMap()))))
}

private val VOLATILE_TAB_FIELDS = setOf("title")
private val VOLATILE_PAGE_FIELDS = setOf("title", "content", "isContentTruncated")
