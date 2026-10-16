package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import org.cef.browser.CefBrowser

/**
 * EDT-confined state projection; native callback arguments are copied before entering this class. A load awaits the
 * server's response until CEF reports the main frame's load start, which follows the navigation's commit. Only a main
 * frame navigation awaits: CEF's loading state also covers frames, so an iframe loading into a finished page is shown
 * as plain loading, as in Chromium.
 */
internal class DesktopBrowserNavigation(
    private val browser: CefBrowser,
    private val changed: (BrowserViewState) -> Unit,
    private val prepareNavigation: (BrowserViewCommand) -> Unit = {},
) {
    private var state = BrowserViewState()
    private var isStopped = false
    private var isMainNavigationPending = false

    fun execute(command: BrowserViewCommand) {
        prepareNavigation(command)
        when (command) {
            is BrowserViewCommand.Load -> {
                if (!isBrowserUrlAllowed(command.url)) {
                    unsupported()
                    return
                }
                state = state.copy(url = command.url, title = "")
                begin { browser.loadURL(command.url) }
            }

            BrowserViewCommand.Back -> if (state.isBackAvailable) begin(browser::goBack)

            BrowserViewCommand.Forward -> if (state.isForwardAvailable) begin(browser::goForward)

            BrowserViewCommand.Reload -> if (state.url.isNotEmpty()) begin(browser::reload)

            BrowserViewCommand.Stop -> {
                isStopped = true
                isMainNavigationPending = false
                browser.stopLoad()
                update(state.copy(isLoading = false, isAwaitingResponse = false))
            }
        }
    }

    fun address(url: String) {
        if (isBrowserUrlAllowed(url)) update(state.copy(url = url))
    }

    fun title(title: String) = update(state.copy(title = title))

    /** The main frame is about to navigate, also when the page itself started it (a link, a form, a script). */
    fun navigating() {
        isMainNavigationPending = true
        if (state.isLoading && !state.isAwaitingResponse) update(state.copy(isAwaitingResponse = true))
    }

    fun loading(isLoading: Boolean, isBackAvailable: Boolean, isForwardAvailable: Boolean) {
        if (!isLoading) {
            // Once a load has ended, loads the page starts itself are shown again; a navigation that never committed
            // (a download, a 204) no longer makes later frame loads look like a wait for the server.
            isStopped = false
            isMainNavigationPending = false
        }
        val isActive = isLoading && !isStopped
        update(
            state.copy(
                isLoading = isActive,
                isAwaitingResponse = isActive && (state.isAwaitingResponse || isMainNavigationPending),
                isBackAvailable = isBackAvailable,
                isForwardAvailable = isForwardAvailable,
            ),
        )
    }

    fun started() {
        isStopped = false
        isMainNavigationPending = false
        update(state.copy(isLoading = true, isAwaitingResponse = false, error = null))
    }

    fun failed() {
        isMainNavigationPending = false
        update(state.copy(isLoading = false, isAwaitingResponse = false, error = BrowserViewError.LoadFailed))
    }

    fun unsupported() {
        isMainNavigationPending = false
        update(state.copy(isLoading = false, isAwaitingResponse = false, error = BrowserViewError.UnsupportedAddress))
    }

    private fun begin(action: () -> Unit) {
        isStopped = false
        isMainNavigationPending = true
        update(state.copy(isLoading = true, isAwaitingResponse = true, error = null))
        action()
    }

    @HighFrequency
    private fun update(next: BrowserViewState) {
        state = next
        changed(next)
    }
}
