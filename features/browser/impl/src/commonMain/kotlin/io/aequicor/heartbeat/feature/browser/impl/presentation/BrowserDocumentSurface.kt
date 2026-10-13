package io.aequicor.heartbeat.feature.browser.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.browser.impl.domain.OpenBrowserDocument

/**
 * An open document as its screen draws it; equal surfaces are the same registration, so recomposition keeps the
 * native view of a document until it closes.
 */
@Immutable
internal data class BrowserDocumentSurface(internal val document: OpenBrowserDocument) {
    /** Accessible name of the page. */
    val title: String get() = document.document.title
}
