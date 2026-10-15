package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.cef.CefApp

/** Lazily initializes the application-owned CEF runtime; cancellation never disposes another surface's app. */
internal fun interface DesktopBrowserResources {
    suspend fun app(): CefApp

    /** App-owned download and installation; immediately emits its current state, including for local documents. */
    val preparation: Flow<BrowserRuntimePreparation> get() = flowOf(BrowserRuntimePreparation.Ready)

    /** Completes native teardown before the desktop host exits; unused runtimes remain uninitialized. */
    suspend fun shutdown() = Unit
}
