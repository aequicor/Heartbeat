package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Browser documents of `web` nodes under trusted live settings. */
internal interface UiWebDocuments {
    /** Whether surfaces may open documents and which resource policy their new pages must use. */
    val configuration: Flow<UiWebConfiguration>

    /** Opens [document], or null when the browser refuses it (over its limits). */
    fun open(document: BrowserDocument): BrowserDocumentHandle?

    /** No web documents: where nothing provides them, `web` nodes stay notes. */
    object Off : UiWebDocuments {
        override val configuration: Flow<UiWebConfiguration> = flowOf(UiWebConfiguration())

        override fun open(document: BrowserDocument): BrowserDocumentHandle? = null
    }
}
