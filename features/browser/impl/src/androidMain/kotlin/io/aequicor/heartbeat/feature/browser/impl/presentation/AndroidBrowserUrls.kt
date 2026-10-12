package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed

/**
 * Hosts that may use cleartext HTTP on Android: the device's loopback and the emulator's alias of the host
 * machine. Must match the `domain-config` of the app's `network_security_config.xml`, which forbids cleartext
 * traffic for every other host.
 */
private val CleartextHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

/**
 * [isBrowserUrlAllowed] narrowed to what Android's network security config lets the WebView load: plain HTTP only
 * for [CleartextHosts]. Other `http://` addresses are rejected up front, so the user sees an unsupported-address
 * error instead of WebView's opaque cleartext block.
 */
internal fun isAndroidBrowserUrlAllowed(url: String): Boolean {
    if (!isBrowserUrlAllowed(url)) return false
    if (!url.startsWith(HTTP_PREFIX, ignoreCase = true)) return true
    val authority = url.substring(HTTP_PREFIX.length).takeWhile { it != '/' && it != '?' && it != '#' }
    // Bracketed IPv6 literals are not listed in the config, so they never count as a cleartext host.
    val host = if (authority.startsWith('[')) authority else authority.substringBefore(':')
    return host.lowercase() in CleartextHosts
}

private const val HTTP_PREFIX = "http://"
