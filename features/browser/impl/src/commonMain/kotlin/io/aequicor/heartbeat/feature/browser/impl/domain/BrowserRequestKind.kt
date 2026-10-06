package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed

/** The destination document, not the frame that initiated a resource request. */
internal enum class BrowserRequestKind { MainDocument, ChildDocument, Subresource }

/**
 * Main documents remain HTTP(S)-only except for the engine's own empty bootstrap.
 * Embedded content may use browser-managed memory URLs; sockets are resources, never documents.
 * Native origin, sandbox, CSP and mixed-content checks still apply after this allowlist.
 * No context may load local files or dispatch a protocol to the operating system.
 */
internal fun isBrowserRequestAllowed(url: String, kind: BrowserRequestKind, isOwnBlank: Boolean = false): Boolean {
    if (isBrowserUrlAllowed(url)) return true
    if (kind == BrowserRequestKind.MainDocument) return isOwnBlank && url == "about:blank"
    return when (url.substringBefore(':').lowercase()) {
        "about" -> url == "about:blank" || (kind == BrowserRequestKind.ChildDocument && url == "about:srcdoc")
        "data" -> ',' in url
        "blob" -> isBrowserBlobAllowed(url.substringAfter(':'))
        "ws", "wss" -> kind == BrowserRequestKind.Subresource && isBrowserSocketAllowed(url)
        else -> false
    }
}

private fun isBrowserBlobAllowed(origin: String): Boolean =
    isBrowserUrlAllowed(origin) || (origin.startsWith("null/") && origin.length > "null/".length)

private fun isBrowserSocketAllowed(url: String): Boolean = isBrowserUrlAllowed("https:" + url.substringAfter(':'))
