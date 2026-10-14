package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter

private val layoutQueryNames = desktopDocumentRouterConfig()

/** A presentation-only size channel, separate from owner calls, including on pages without a method bridge. */
internal val desktopDocumentLayoutFunction: String get() = layoutQueryNames.jsQueryFunction

/**
 * One-shot size reports from the document's main frame. This channel cannot execute a method, navigate, or access
 * data. Host bounds and generation checks apply even to a page that replaces its resize observer with arbitrary JS.
 */
internal class DesktopDocumentLayoutRouter(url: String, private val measurements: DesktopDocumentMeasurements) {
    private val handler = object : CefMessageRouterHandlerAdapter() {
        @HighFrequency
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean {
            if (persistent || !frame.isMain || !isDesktopDocumentFrame(url, frame.url)) {
                callback.failure(0, "The layout report is not allowed")
            } else {
                measurements.offer(request)
                callback.success("null")
            }
            return true
        }
    }

    val router: CefMessageRouter = CefMessageRouter.create(layoutQueryNames).also { it.addHandler(handler, true) }

    fun close() {
        measurements.close()
        router.removeHandler(handler)
    }
}
