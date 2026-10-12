package io.aequicor.heartbeat.feature.harness.impl.domain

import kotlinx.coroutines.CoroutineScope

/** Starts hot subscriptions before library activation, in the caller's enabled-branch lifetime. */
internal interface HarnessEnabledWork {
    fun start(scope: CoroutineScope)
    fun stop()
}
