package io.aequicor.heartbeat.feature.browser.impl.domain

/**
 * Platform resource port retained with one browser session. Platform-specific domain interfaces refine
 * this marker; presentation consumes those interfaces without accessing their data implementations.
 * Supplying resources does not authorize network access: the native backend must verify its isolation.
 */
internal interface BrowserNativeResources {
    /** Platforms without additional resource services use an explicit empty port. */
    data object None : BrowserNativeResources
}
