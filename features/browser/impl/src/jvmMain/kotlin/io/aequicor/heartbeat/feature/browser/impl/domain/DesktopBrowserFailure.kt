package io.aequicor.heartbeat.feature.browser.impl.domain

/**
 * Keeps the exception type and stack frames while stripping messages, causes and suppressed errors.
 * Native failures can contain private URLs, page titles, query parameters and filesystem paths.
 */
internal fun Throwable.desktopBrowserFailure(): Throwable =
    IllegalStateException(javaClass.name).also { it.stackTrace = stackTrace }
