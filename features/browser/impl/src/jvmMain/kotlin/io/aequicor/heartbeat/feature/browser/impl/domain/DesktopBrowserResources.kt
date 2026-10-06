package io.aequicor.heartbeat.feature.browser.impl.domain

import org.cef.CefApp

/** Lazily initializes the application-owned CEF runtime; cancellation never disposes another surface's app. */
internal fun interface DesktopBrowserResources {
    suspend fun app(): CefApp
}
