package io.aequicor.heartbeat.feature.browser.impl.domain

/** Shared preparation of the browser component; individual pages never own or cancel this operation. */
internal sealed interface BrowserRuntimePreparation {
    data object Idle : BrowserRuntimePreparation
    data class Downloading(val bytes: Long, val total: Long?) : BrowserRuntimePreparation
    data object Installing : BrowserRuntimePreparation
    data object Ready : BrowserRuntimePreparation
    data class Failed(val reason: BrowserRuntimeFailure) : BrowserRuntimePreparation
}

/** Safe error categories shown without filesystem paths, network URLs or native diagnostics. */
internal enum class BrowserRuntimeFailure {
    Network,
    Storage,
    InvalidRuntime,
    Unavailable,
}
