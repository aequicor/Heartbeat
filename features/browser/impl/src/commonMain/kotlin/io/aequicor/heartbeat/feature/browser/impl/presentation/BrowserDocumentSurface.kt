package io.aequicor.heartbeat.feature.browser.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPresentation
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentSizing
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentNatives
import io.aequicor.heartbeat.feature.browser.impl.domain.OpenBrowserDocument

/**
 * An open document as its screen draws it with the platform's [natives]; equal surfaces are the same registration,
 * so recomposition keeps the native view of a document until it closes.
 */
@Immutable
internal data class BrowserDocumentSurface(
    internal val document: OpenBrowserDocument,
    internal val natives: BrowserDocumentNatives = BrowserDocumentNatives.None,
    internal val presentation: BrowserDocumentPresentation = BrowserDocumentPresentation.Document,
) {
    /** Accessible name of the page. */
    val title: String get() = document.document.title

    /** Whether this route may follow the page's intrinsic height inside host bounds. */
    val isContentSized: Boolean
        get() = isHostContentSized || (!isFill && document.document.sizing == BrowserDocumentSizing.Content)

    /** Intrinsic height follows this host's constraints without applying the registration's viewport presets. */
    val isHostContentSized: Boolean get() = presentation == BrowserDocumentPresentation.Content

    /** Whether this host fills its viewport instead of following the registration's intrinsic height. */
    val isFill: Boolean get() = presentation == BrowserDocumentPresentation.Fill
}
