package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed

/** Native evidence for a bounded HTTP(S) redirect chain that cannot downgrade after reaching HTTPS. */
internal class DesktopBrowserRedirects(url: String) {
    private var isHttpsRequired = url.startsWith("https://", ignoreCase = true)
    private var redirects = 0
    private var responseUrl: String? = null
    private var isRejected = false

    init {
        require(isBrowserUrlAllowed(url)) { "Invalid source URL" }
    }

    @Synchronized
    fun allows(url: String): Boolean {
        val isHttps = url.startsWith("https://", ignoreCase = true)
        val isAllowed = !isRejected && isBrowserUrlAllowed(url) && (!isHttpsRequired || isHttps)
        if (isAllowed && isHttps) isHttpsRequired = true
        return isAllowed
    }

    @Synchronized
    fun redirect(url: String): Boolean {
        redirects++
        responseUrl = null
        isRejected = !allows(url) || redirects > MAX_REDIRECTS
        return !isRejected
    }

    @Synchronized
    fun response(url: String): Boolean {
        isRejected = !allows(url)
        if (!isRejected) responseUrl = url
        return !isRejected
    }

    @Synchronized
    fun finalUrl(): String {
        check(!isRejected) { "Source redirect was rejected" }
        return checkNotNull(responseUrl) { "Source response was not validated" }
    }

    private companion object {
        const val MAX_REDIRECTS = 10
    }
}
