package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import org.cef.handler.CefLoadHandler.ErrorCode
import org.cef.handler.CefRequestContextHandlerAdapter
import org.cef.network.CefURLRequest.Status

/**
 * Standalone URLRequest does not emit resource-handler callbacks. STOP_ON_REDIRECT makes each
 * completed response evidence for exactly one requested hop; the next URL is validated before IO, also by the
 * caller's [allows] (an agent tab's guard), which may be called on a native thread.
 */
internal class DesktopBrowserTransferPolicy(
    url: String,
    private val urlResolver: BrowserUrlResolver,
    private val allows: (String) -> Boolean = { true },
) : CefRequestContextHandlerAdapter() {
    private val redirects = DesktopBrowserRedirects(url)

    init {
        require(allows(url)) { "Source URL was rejected" }
    }
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
            check(allows(destination) && redirects.redirect(destination)) { "Source redirect was rejected" }
            currentUrl = destination
            return false
        }
        check(requestStatus == Status.UR_SUCCESS) { "Source still unavailable" }
        if (status !in SUCCESS_CODES) throw DesktopBrowserSourceRejected("Source still unavailable", status)
        check(redirects.response(currentUrl)) { "Source response URL was rejected" }
        return true
    }

    fun finalUrl(): String = redirects.finalUrl()

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val SUCCESS_CODES = 200..299
    }
}

/**
 * The server answered, but not with a usable resource: a final HTTP status outside 2xx ([status]) or a body over its
 * limit (no status). Unless [isRetryable], asking the same address again gets the same answer.
 */
internal class DesktopBrowserSourceRejected(message: String, val status: Int? = null) : IllegalStateException(message) {
    val isRetryable: Boolean
        get() = status != null && (status in RETRYABLE_STATUSES || status in SERVER_ERRORS)

    private companion object {
        val RETRYABLE_STATUSES = setOf(408, 425, 429)
        val SERVER_ERRORS = 500..599
    }
}
