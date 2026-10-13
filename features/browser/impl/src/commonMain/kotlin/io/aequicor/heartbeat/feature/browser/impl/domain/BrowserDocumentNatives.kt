package io.aequicor.heartbeat.feature.browser.impl.domain

/**
 * Platform runtime that draws documents, retained with the profile. Platform interfaces refine this marker, the way
 * [BrowserNativeResources] works for tabs; platforms without one use [None] and draw a note instead.
 */
internal interface BrowserDocumentNatives {
    /** No document runtime on this platform. */
    data object None : BrowserDocumentNatives
}
