package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources

/** Commands consumed by one native view on its platform UI thread. */
internal sealed interface BrowserViewCommand {
    data class Load(val url: String) : BrowserViewCommand
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
)

internal enum class BrowserViewError { LoadFailed, UnsupportedAddress, EngineUnavailable }

/** The implementation schedules commands onto its native UI thread. */
internal fun interface BrowserViewController {
    fun execute(command: BrowserViewCommand)
}

/**
 * Lifecycle callbacks for a native browser surface. The retained model rejects callbacks from
 * detached controllers. A fresh surface restores the current URL; history belongs to that surface.
 */
internal interface BrowserSurface {
    /** Injected platform services; each native backend checks the refined domain contract before use. */
    val nativeResources: BrowserNativeResources
    val initialUrl: String
    fun attached(controller: BrowserViewController)
    fun changed(controller: BrowserViewController, state: BrowserViewState)
    fun detached(controller: BrowserViewController)
}
