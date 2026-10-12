package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import org.cef.handler.CefLoadHandler.ErrorCode
import org.cef.handler.CefRequestContextHandlerAdapter
import org.cef.network.CefURLRequest.Status

/**
 * Standalone URLRequest does not emit resource-handler callbacks. STOP_ON_REDIRECT makes each
 * completed response evidence for exactly one requested hop; the next URL is validated before IO.
 */
internal class DesktopBrowserTransferPolicy(url: String, private val urlResolver: BrowserUrlResolver) :
    CefRequestContextHandlerAdapter() {
    private val redirects = DesktopBrowserRedirects(url)
    var currentUrl: String = url
        private set

    /** Returns true only for a successful final response; false advances to a validated redirect. */
    fun accept(status: Int, requestStatus: Status, error: ErrorCode, location: String?): Boolean {
        if (status in REDIRECT_CODES) {
            // CEF 152 OnRedirect saves response headers then Cancel() reports precisely this pair.
            check(requestStatus == Status.UR_CANCELED && error == ErrorCode.ERR_ABORTED) {
                "Source redirect did not stop before navigation"
            }
            check(!location.isNullOrBlank()) { "Source redirect has no destination" }
            val destination = urlResolver.resolve(currentUrl, location)
            check(redirects.redirect(destination)) { "Source redirect was rejected" }
            currentUrl = destination
            return false
        }
        check(requestStatus == Status.UR_SUCCESS && status in SUCCESS_CODES) { "Source still unavailable" }
        check(redirects.response(currentUrl)) { "Source response URL was rejected" }
        return true
    }

    fun finalUrl(): String = redirects.finalUrl()

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val SUCCESS_CODES = 200..299
    }
}
