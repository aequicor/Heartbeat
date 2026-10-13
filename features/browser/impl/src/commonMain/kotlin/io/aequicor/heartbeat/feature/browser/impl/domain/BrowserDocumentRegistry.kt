package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * A document registered through `BrowserDocuments`: [id] names its route and origin, [data] is what its page reads
 * now. Every registration has its own id: reopening the same HTML is another document.
 */
internal data class OpenBrowserDocument(
    val id: String,
    val document: BrowserDocument,
    val data: StateFlow<JsonElement?>,
) {
    override fun toString(): String = "OpenBrowserDocument($document)"
}

/** Documents of the profile as the routes that draw them see them. */
internal interface BrowserDocumentRegistry {
    /** Open documents by id; a closed document leaves the map. */
    val documents: StateFlow<Map<String, OpenBrowserDocument>>
}
