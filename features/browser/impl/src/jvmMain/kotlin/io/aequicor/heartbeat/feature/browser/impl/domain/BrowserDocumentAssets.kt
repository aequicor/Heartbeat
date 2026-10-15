package io.aequicor.heartbeat.feature.browser.impl.domain

/** Opens an anonymous, bounded HTTP asset budget for one temporary document. */
internal fun interface BrowserDocumentAssets {
    fun open(): BrowserDocumentAssetSession

    /** Safe documents never create an HTTP client session. */
    object None : BrowserDocumentAssets {
        override fun open(): BrowserDocumentAssetSession = BrowserDocumentAssetSession { null }
    }
}

/**
 * Loads one GET hop. The caller owns cancellation and handles redirects through Chromium, preserving the final
 * URL used by relative CSS imports and module imports. Failures and exhausted budgets return null.
 */
internal fun interface BrowserDocumentAssetSession {
    suspend fun load(url: String): BrowserDocumentAssetResult?
}

/** In-memory response metadata only: no cookies, credentials or browser request headers cross this port. */
internal sealed interface BrowserDocumentAssetResult {
    data class Content(val body: ByteArray, val mimeType: String, val headers: Map<String, String>) :
        BrowserDocumentAssetResult {
        override fun toString(): String = "Content(bytes=${body.size}, metadata=redacted)"
    }

    data class Redirect(val url: String, val headers: Map<String, String> = emptyMap()) : BrowserDocumentAssetResult {
        override fun toString(): String = "Redirect(url=redacted, metadata=redacted)"
    }
}
