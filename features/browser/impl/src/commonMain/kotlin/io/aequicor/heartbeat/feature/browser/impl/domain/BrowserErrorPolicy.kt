package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlinx.coroutines.flow.Flow

/** Opt-in policy for browser-owned failure pages and recovery. */
internal interface BrowserErrorPolicy {
    fun observe(): Flow<Boolean>
}

/** Emits a signal only after a previously observed network link becomes available again. */
internal interface BrowserConnectivity {
    fun restored(): Flow<Unit>
}
