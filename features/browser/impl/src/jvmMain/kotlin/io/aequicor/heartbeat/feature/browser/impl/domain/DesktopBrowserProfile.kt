package io.aequicor.heartbeat.feature.browser.impl.domain

import org.cef.browser.CefRequestContext

/** Profile-owned persistent context; presentation contributes only asynchronous surface closure. */
internal interface DesktopBrowserProfile {
    val isAvailable: Boolean
    fun attach(closeSurfaces: suspend () -> Unit)
    suspend fun <T> useContext(block: (CefRequestContext) -> T): T
    suspend fun clearData()
}
