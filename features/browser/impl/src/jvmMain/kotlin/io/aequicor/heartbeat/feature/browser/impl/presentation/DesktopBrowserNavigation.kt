package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import org.cef.browser.CefBrowser
import kotlin.uuid.Uuid

/**
 * EDT-confined state projection; native callback arguments are copied before entering this class. A load awaits the
 * server's response until CEF reports the main frame's load start, which follows the navigation's commit. Only a main
 * frame navigation awaits: CEF's loading state also covers frames, so an iframe loading into a finished page is shown
 * as plain loading, as in Chromium.
 * The connection indicator describes the last committed document while the address still shows it: a page-started
 * load keeps it until the next commit; a typed or stopped address that was never committed, and a load failure,
 * show no indicator.
 */
internal class DesktopBrowserNavigation(
    private val browser: CefBrowser,
    private val changed: (BrowserViewState) -> Unit,
    private val prepareNavigation: (BrowserViewCommand) -> Unit = {},
    private val security: (String) -> BrowserSecurity = ::browserConnectionSecurity,
) {
    private var state = BrowserViewState()
    private var isStopped = false
    private var isMainNavigationPending = false
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
                isMainNavigationPending = false
                browser.stopLoad()
                update(state.copy(isLoading = false, isAwaitingResponse = false))
            }
        }
    }

    fun address(url: String) {
        if (!isBrowserUrlAllowed(url)) return
        // Chromium's own error document commits the failed URL; it is not a page whose connection we can describe.
        if (state.failure == null) committedUrl = url
        // The indicator compares the committed address with the one now shown, so the address changes first.
        state = state.copy(url = url)
        update(state.copy(security = connectionSecurity()))
    }

    private fun connectionSecurity(): BrowserSecurity {
        val isFailed = state.failure != null || state.error == BrowserViewError.LoadFailed ||
            state.error == BrowserViewError.EngineUnavailable
        return if (isFailed || state.url != committedUrl) BrowserSecurity.Unknown else security(committedUrl)
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
                security = connectionSecurity(),
                isAwaitingResponse = isActive && (state.isAwaitingResponse || isMainNavigationPending),
                isBackAvailable = isBackAvailable,
                isForwardAvailable = isForwardAvailable,
            ),
        )
    }

    fun started(url: String = state.url) {
        // Chromium commits its own error document after onLoadError. It must not erase our warning.
        if (!isBrowserUrlAllowed(url)) return
        isStopped = false
        isMainNavigationPending = false
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
        isMainNavigationPending = false
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

    fun unsupported() {
        isMainNavigationPending = false
        update(state.copy(isLoading = false, isAwaitingResponse = false, error = BrowserViewError.UnsupportedAddress))
    }

    private fun begin(action: () -> Unit) {
        isStopped = false
        isMainNavigationPending = true
        state = state.copy(isLoading = true, isAwaitingResponse = true, error = null, failure = null)
        update(state.copy(security = connectionSecurity()))
        action()
    }

    @HighFrequency
    private fun update(next: BrowserViewState) {
        state = next
        changed(next)
    }
}
