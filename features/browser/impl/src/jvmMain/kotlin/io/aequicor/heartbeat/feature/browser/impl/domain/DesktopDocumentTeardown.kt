package io.aequicor.heartbeat.feature.browser.impl.domain

/** Closes every document browser of the app before the CEF runtime is disposed. */
internal fun interface DesktopDocumentTeardown {
    /** Releases the document browsers still shown and waits until each has closed natively. */
    suspend fun closeDocuments()

    /** No document browsers to close (tests of the runtime without documents). */
    companion object {
        /** Nothing to close. */
        val None: DesktopDocumentTeardown = DesktopDocumentTeardown {}
    }
}
