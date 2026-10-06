package io.aequicor.heartbeat.feature.browser.impl.domain

/** A provisional request may have failed while the native view still displays a previous document. */
internal sealed interface BrowserReloadDecision {
    data object Ignore : BrowserReloadDecision
    data object ReloadCommitted : BrowserReloadDecision
    data class LoadRequested(val url: String) : BrowserReloadDecision
}

/** Uses native reload semantics only when the requested document is the one that actually committed. */
internal fun browserReloadDecision(requestedAddress: String, committedAddress: String): BrowserReloadDecision = when {
    requestedAddress.isEmpty() -> BrowserReloadDecision.Ignore
    requestedAddress == committedAddress -> BrowserReloadDecision.ReloadCommitted
    else -> BrowserReloadDecision.LoadRequested(requestedAddress)
}
