package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import org.cef.browser.CefBrowser
import kotlin.uuid.Uuid

/**
 * EDT-confined state projection; native callback arguments are copied before entering this class. A load awaits the
 * server's response until CEF reports the main frame's load start, which follows the navigation's commit.
 * The connection indicator describes the last committed document, like Chrome's: a pending, stopped or typed load
 * keeps it until the next commit, and only a load failure drops it.
 */
internal class DesktopBrowserNavigation(
    private val browser: CefBrowser,
    private val changed: (BrowserViewState) -> Unit,
    private val prepareNavigation: (BrowserViewCommand) -> Unit = {},
    private val security: (String) -> BrowserSecurity = ::browserConnectionSecurity,
) {
    private var state = BrowserViewState()
    private var isStopped = false
    private var committedUrl = ""

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
                browser.stopLoad()
                update(state.copy(isLoading = false, isAwaitingResponse = false))
            }
        }
    }

    fun address(url: String) {
        if (!isBrowserUrlAllowed(url)) return
        // Chromium's own error document commits the failed URL; it is not a page whose connection we can describe.
        if (state.failure == null) committedUrl = url
        update(state.copy(url = url, security = connectionSecurity()))
    }

    private fun connectionSecurity(): BrowserSecurity {
        val isFailed = state.failure != null || state.error == BrowserViewError.LoadFailed ||
            state.error == BrowserViewError.EngineUnavailable
        return if (isFailed) BrowserSecurity.Unknown else security(committedUrl)
    }

    fun title(title: String) = update(state.copy(title = title))

    fun loading(isLoading: Boolean, isBackAvailable: Boolean, isForwardAvailable: Boolean) {
        val isActive = isLoading && !isStopped
        update(
            state.copy(
                isLoading = isActive,
                security = connectionSecurity(),
                // A load the page started itself (a link, a redirect) begins here, before any response.
                isAwaitingResponse = isActive && (state.isAwaitingResponse || !state.isLoading),
                isBackAvailable = isBackAvailable,
                isForwardAvailable = isForwardAvailable,
            ),
        )
    }

    fun started(url: String = state.url) {
        // Chromium commits its own error document after onLoadError. It must not erase our warning.
        if (!isBrowserUrlAllowed(url)) return
        isStopped = false
        update(
            state.copy(
                isLoading = true,
                isAwaitingResponse = false,
                error = null,
                failure = null,
                isCertificateWarningActive = false,
            ),
        )
    }

    fun failed(url: String = state.url, code: String = "ERR_FAILED", isSubmission: Boolean = false) {
        if (url.isNotEmpty() && !isBrowserUrlAllowed(url)) return
        val isCertificate = code.startsWith("ERR_CERT_") || code == "ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN"
        committedUrl = ""
        update(
            state.copy(
                url = url,
                isLoading = false,
                isAwaitingResponse = false,
                error = BrowserViewError.LoadFailed,
                isCertificateWarningActive = state.isCertificateWarningActive || isCertificate,
                failure = BrowserLoadFailure(
                    url,
                    code,
                    isCertificate = isCertificate,
                    id = Uuid.random().toString(),
                    isSubmission = isSubmission,
                ),
                security = BrowserSecurity.Unknown,
            ),
        )
    }

    fun unsupported() =
        update(state.copy(isLoading = false, isAwaitingResponse = false, error = BrowserViewError.UnsupportedAddress))

    private fun begin(action: () -> Unit) {
        isStopped = false
        update(
            state.copy(
                isLoading = true,
                isAwaitingResponse = true,
                error = null,
                failure = null,
            ),
        )
        action()
    }

    @HighFrequency
    private fun update(next: BrowserViewState) {
        state = next
        changed(next)
    }
}
