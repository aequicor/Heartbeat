package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import org.cef.browser.CefBrowser

/** EDT-confined state projection; native callback arguments are copied before entering this class. */
internal class DesktopBrowserNavigation(
    private val browser: CefBrowser,
    private val changed: (BrowserViewState) -> Unit,
) {
    private var state = BrowserViewState()
    private var isStopped = false

    fun execute(command: BrowserViewCommand) {
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
                browser.stopLoad()
                update(state.copy(isLoading = false))
            }
        }
    }

    fun address(url: String) {
        if (isBrowserUrlAllowed(url)) update(state.copy(url = url))
    }

    fun title(title: String) = update(state.copy(title = title))

    fun loading(isLoading: Boolean, isBackAvailable: Boolean, isForwardAvailable: Boolean) {
        update(
            state.copy(
                isLoading = isLoading && !isStopped,
                isBackAvailable = isBackAvailable,
                isForwardAvailable = isForwardAvailable,
            ),
        )
    }

    fun started() {
        isStopped = false
        update(state.copy(isLoading = true, error = null))
    }

    fun failed() = update(state.copy(isLoading = false, error = BrowserViewError.LoadFailed))

    fun unsupported() = update(state.copy(isLoading = false, error = BrowserViewError.UnsupportedAddress))

    private fun begin(action: () -> Unit) {
        isStopped = false
        update(state.copy(isLoading = true, error = null))
        action()
    }

    @HighFrequency
    private fun update(next: BrowserViewState) {
        state = next
        changed(next)
    }
}
