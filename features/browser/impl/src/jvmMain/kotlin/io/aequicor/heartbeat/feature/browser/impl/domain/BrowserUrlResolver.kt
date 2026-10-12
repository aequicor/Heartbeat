package io.aequicor.heartbeat.feature.browser.impl.domain

/** Resolves a redirect reference without IO; malformed references fail before another request starts. */
internal fun interface BrowserUrlResolver {
    fun resolve(base: String, reference: String): String
}
