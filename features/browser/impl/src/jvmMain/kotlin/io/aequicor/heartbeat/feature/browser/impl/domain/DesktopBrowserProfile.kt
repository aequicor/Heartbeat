package io.aequicor.heartbeat.feature.browser.impl.domain

import org.cef.browser.CefRequestContext

/**
 * Profile-owned Chromium contexts; presentation contributes only asynchronous surface closure. The persistent
 * context holds the user's storage ([BrowserStorage.User]); each agent session has its own in-memory context
 * ([BrowserStorage.Agent]) that shares nothing with it.
 */
internal interface DesktopBrowserProfile {
    val isAvailable: Boolean
    fun attach(closeSurfaces: suspend () -> Unit)
    suspend fun <T> useContext(block: (CefRequestContext) -> T): T

    /** Runs [block] with the in-memory context of the agent session [owner], created empty on first use. */
    suspend fun <T> useAgentContext(owner: String, block: (CefRequestContext) -> T): T

    /**
     * Discards the in-memory context of [owner] unless [isInUse] reports a tab that still needs it. [isInUse] is
     * evaluated under the lock that also guards [useAgentContext], so a tab created meanwhile keeps its context.
     */
    suspend fun releaseAgentContext(owner: String, isInUse: () -> Boolean)
    suspend fun clearData()
}
