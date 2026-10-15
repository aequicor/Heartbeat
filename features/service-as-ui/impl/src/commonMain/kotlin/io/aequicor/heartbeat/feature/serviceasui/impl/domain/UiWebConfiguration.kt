package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy

/** Trusted settings of a web document; a change replaces its page and revokes its previous resource policy. */
internal data class UiWebConfiguration(
    val isEnabled: Boolean = false,
    val resourcePolicy: BrowserDocumentResourcePolicy = BrowserDocumentResourcePolicy.Isolated,
)
