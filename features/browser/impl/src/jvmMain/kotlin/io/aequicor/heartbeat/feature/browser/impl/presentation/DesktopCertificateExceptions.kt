package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import java.util.concurrent.ConcurrentHashMap

/**
 * Conservative profile-lifetime host labels for Chromium's own certificate exceptions. This registry grants nothing:
 * Only Chromium's native interstitial may authorize a certificate. Intercepted agent requests to those hosts
 * are blocked across ports, since CEF can silently reuse a user-approved certificate in another tab; so are hosts
 * a user tab was warned about (a native bypass is not reported) and every HTTPS host while decisions are unreadable.
 * Restored from the profile journal and native preferences before navigation; cleared with browser data.
 * This is not network isolation: CEF does not intercept WebSocket handshakes (see [DesktopBrowserPolicy]).
 */
internal class DesktopCertificateExceptions {
    private val hosts = ConcurrentHashMap.newKeySet<String>()

    /** Hosts whose interstitial a user tab showed; Chromium may hold their decision before any page load ends. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** False while Chromium's decisions could not be read: an agent tab then must not trust any HTTPS host. */
    @Volatile
    var areDecisionsKnown: Boolean = true
        private set

    fun remember(url: String) {
        certificateHost(url)?.let(hosts::add)
    }

    fun contains(url: String): Boolean = certificateHost(url)?.let { it in hosts } == true

    /**
     * A user tab is about to show Chromium's interstitial for [url]. Its native bypass link (or a typed bypass)
     * stores the decision without telling us, so agent requests to the host are blocked from now on; the visible
     * red label still waits for the decision itself.
     */
    fun warned(url: String) {
        certificateHost(url)?.let(pending::add)
    }

    /** Whether an agent tab must not load [url]: a decision exists, may exist, or decisions are unreadable. */
    fun isWithheldFromAgents(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val host = certificateHost(url)
        return !areDecisionsKnown || host == null || host in hosts || host in pending
    }

    /** Reads only host keys from the native profile; expired entries remain conservatively labelled until clear. */
    fun restore(preference: Any?): Boolean {
        val settings = preference as? Map<*, *>
        areDecisionsKnown = settings != null
        settings?.keys?.filterIsInstance<String>()?.forEach { remember(it.substringBefore(',')) }
        return settings != null
    }

    fun urls(): List<String> = hosts.map { "https://$it/" }.sorted()

    fun clear() {
        hosts.clear()
        pending.clear()
    }

    private fun certificateHost(url: String): String? = browserOrigin(url)
        ?.takeIf { it.startsWith("https://") }?.substringAfter("://")?.substringBeforeLast(':')

    fun security(url: String): BrowserSecurity =
        if (contains(url)) BrowserSecurity.CertificateException else browserConnectionSecurity(url)
}

internal fun browserConnectionSecurity(url: String): BrowserSecurity = when {
    url.startsWith("https://", ignoreCase = true) -> BrowserSecurity.Https
    url.startsWith("http://", ignoreCase = true) -> BrowserSecurity.Http
    else -> BrowserSecurity.Unknown
}

internal const val SSL_CERTIFICATE_DECISIONS = "profile.content_settings.exceptions.ssl_cert_decisions"
