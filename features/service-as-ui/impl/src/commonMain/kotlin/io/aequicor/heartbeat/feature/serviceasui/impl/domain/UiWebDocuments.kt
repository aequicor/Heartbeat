package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Isolated browser documents of `web` nodes; [availability] follows the `service_ui.web` toggle. */
internal interface UiWebDocuments {
    /** Whether surfaces may open documents now. */
    val availability: Flow<Boolean>

    /** Opens [document], or null when the browser refuses it (over its limits). */
    fun open(document: BrowserDocument): BrowserDocumentHandle?

    /** No web documents: where nothing provides them, `web` nodes stay notes. */
    object Off : UiWebDocuments {
        override val availability: Flow<Boolean> = flowOf(false)

        override fun open(document: BrowserDocument): BrowserDocumentHandle? = null
    }
}
