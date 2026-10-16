package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import java.util.concurrent.ConcurrentHashMap

/**
 * Conservative profile-lifetime host labels for Chromium's own certificate exceptions. This registry grants nothing:
 * Only Chromium's native interstitial may authorize a certificate. Intercepted agent requests to those hosts
 * are blocked across ports, since CEF can silently reuse a user-approved certificate in another tab.
 * Restored from the profile journal and native preferences before navigation; cleared with browser data.
 * This is not network isolation: CEF does not intercept WebSocket handshakes (see [DesktopBrowserPolicy]).
 */
internal class DesktopCertificateExceptions {
    private val hosts = ConcurrentHashMap.newKeySet<String>()

    fun remember(url: String) {
        certificateHost(url)?.let(hosts::add)
    }

    fun contains(url: String): Boolean = certificateHost(url)?.let { it in hosts } == true

    /** Reads only host keys from the native profile; expired entries remain conservatively labelled until clear. */
    fun restore(preference: Any?): Boolean {
        val settings = preference as? Map<*, *> ?: return false
        settings.keys.filterIsInstance<String>().forEach { remember(it.substringBefore(',')) }
        return true
    }

    fun urls(): List<String> = hosts.map { "https://$it/" }.sorted()

    fun clear() = hosts.clear()

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
