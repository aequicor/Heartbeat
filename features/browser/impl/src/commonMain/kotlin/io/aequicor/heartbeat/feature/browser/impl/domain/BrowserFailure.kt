package io.aequicor.heartbeat.feature.browser.impl.domain

/**
 * Loggable copy of a browser failure: keeps the exception type and, where the platform allows, its stack
 * frames, but strips messages, causes and suppressed errors. Native failures can contain private URLs,
 * page titles, query parameters and filesystem paths.
 */
internal expect fun Throwable.redactedBrowserFailure(): Throwable
