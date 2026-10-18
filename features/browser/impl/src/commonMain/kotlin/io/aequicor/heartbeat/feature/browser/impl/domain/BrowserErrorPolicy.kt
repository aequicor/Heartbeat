package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/** Policy for browser-owned failure pages and recovery. */
internal interface BrowserErrorPolicy {
    fun observe(): Flow<Boolean>
}

/** Emits a signal only after a previously observed network link becomes available again. */
internal interface BrowserConnectivity {
    fun restored(): Flow<Unit>
}

/** Observes [restored] only while [this] policy is enabled: a disabled feature performs no network inspection. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.networkRecoveriesWhileEnabled(restored: () -> Flow<Unit>): Flow<Unit> =
    distinctUntilChanged().flatMapLatest { isEnabled -> if (isEnabled) restored() else emptyFlow() }
