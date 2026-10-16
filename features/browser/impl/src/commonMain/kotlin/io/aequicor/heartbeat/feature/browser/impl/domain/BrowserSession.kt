package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserTab
import io.aequicor.heartbeat.feature.browser.api.BrowserZoom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Availability is read through a port; domain does not know the toggle implementation. */
internal fun interface BrowserAvailability {
    fun observe(): Flow<Boolean>
}

/** Commands and current page of the retained native browsing session. */
internal interface BrowserSession {
    val errorPagesEnabled: Flow<Boolean> get() = flowOf(false)
    val networkRestored: Flow<Unit> get() = emptyFlow()
    suspend fun proceedCertificate(tabId: String, failure: BrowserLoadFailure): Unit = Unit
    suspend fun returnToSafety(tabId: String, failure: BrowserLoadFailure): Unit = Unit
    suspend fun retryNetworkErrors(): Unit = Unit

    val tabs: Flow<Pair<List<BrowserTab>, String?>>
        get() = flowOf(emptyList<BrowserTab>() to null)

    suspend fun perform(request: BrowserRequest): BrowserResult =
        BrowserResult(request.id, error = "Desktop browser required")

    suspend fun selectTab(id: String): Unit = Unit

    suspend fun cancelOperation(id: String): Unit = Unit

    /** Whether this session's surfaces can zoom pages; platforms without tabs cannot. */
    val isZoomSupported: Boolean
        get() = false

    /** Applies the live page zoom toggle; a disabled zoom shows every page at its default level. */
    suspend fun setZoomEnabled(isEnabled: Boolean): Unit = Unit

    /**
     * Applies [direction] to the latest site level in command order, updating all its tabs and future visits.
     * [url] binds the expected host before waiting for IO; a navigated or closed target is ignored.
     */
    suspend fun zoom(tabId: String?, direction: BrowserZoom, url: String? = null): Unit = Unit

    val pages: Flow<BrowserPage>
    suspend fun setEnabled(isEnabled: Boolean)
    suspend fun execute(command: BrowserCommand)
}

/** Domain commands are mapped to platform controller commands by presentation. */
internal sealed interface BrowserCommand {
    data class Load(val url: String, val referrer: BrowserReferrer? = null) : BrowserCommand
    data object Back : BrowserCommand
    data object Forward : BrowserCommand
    data object Reload : BrowserCommand
    data object Stop : BrowserCommand
}

/** The page zoom toggle, read through a port like availability. */
internal fun interface BrowserZoomPolicy {
    fun observe(): Flow<Boolean>
}

/** Agent control policy is observed for revocation and rechecked after queued operations. */
internal interface BrowserAgentPolicy {
    fun observe(): Flow<Boolean>

    suspend fun isEnabled(): Boolean
}
