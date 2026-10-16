package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserReferrer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Commands consumed by one native view on its platform UI thread. */
internal sealed interface BrowserViewCommand {
    data class Load(val url: String, val referrer: BrowserReferrer? = null) : BrowserViewCommand
    data object Back : BrowserViewCommand
    data object Forward : BrowserViewCommand
    data object Reload : BrowserViewCommand
    data object Stop : BrowserViewCommand
}

/** Native main-frame state; URLs and titles must never be included in logs. */
internal data class BrowserViewState(
    val url: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isBackAvailable: Boolean = false,
    val isForwardAvailable: Boolean = false,
    val error: BrowserViewError? = null,
    val isAwaitingResponse: Boolean = false,
    val failure: BrowserLoadFailure? = null,
    /** Survives a cancelled navigation until a new main document replaces the native certificate warning. */
    val isCertificateWarningActive: Boolean = false,
    val security: BrowserSecurity = BrowserSecurity.Unknown,
)

internal enum class BrowserViewError {
    LoadFailed,
    UnsupportedAddress,
    EngineUnavailable,
}

/** The implementation schedules commands onto its native UI thread. */
internal fun interface BrowserViewController {
    fun execute(command: BrowserViewCommand)
}

/**
 * Lifecycle callbacks for a native browser surface. The retained model rejects callbacks from
 * detached controllers. A fresh surface restores the current URL; history belongs to that surface.
 */
internal interface BrowserSurface {
    val hasTabs: Boolean
        get() = false

    /** Decoded site icons by address for the tab strip; platforms without tabs have none. */
    val favicons: StateFlow<Map<String, BrowserFaviconImage>>
        get() = NoFavicons

    /** Tab keys pressed inside a page, which never reach Compose; platforms without tabs have none. */
    val shortcuts: Flow<BrowserShortcut>
        get() = emptyFlow()

    /** A stable surface for the selected desktop tab; mobile keeps the single surface. */
    fun forTab(id: String?): BrowserSurface = this

    /**
     * Injected platform services; each native backend checks the refined domain contract before
     * use.
     */
    val nativeResources: BrowserNativeResources
    val initialUrl: String
    fun attached(controller: BrowserViewController)
    fun changed(controller: BrowserViewController, state: BrowserViewState)
    fun detached(controller: BrowserViewController)
}

private val NoFavicons: StateFlow<Map<String, BrowserFaviconImage>> = MutableStateFlow(emptyMap())
