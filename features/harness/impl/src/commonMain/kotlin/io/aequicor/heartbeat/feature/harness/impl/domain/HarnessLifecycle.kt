package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Starts lazily on the first enable. Closing an enabled branch suspends even an in-flight initial load.
 * Machine effects belong to the profile, not this branch: saving and its receipt survive global suspension.
 */
internal suspend fun followHarnessToggle(machine: Lazy<HarnessMachine>, enabled: Flow<Boolean>) {
    enabled.distinctUntilChanged().collectLatest { isEnabled ->
        if (isEnabled) {
            coroutineScope {
                val library = machine.value
                try {
                    if (library.state.value.isSuspended) library.send(HarnessIntent.Internal.Resumed)
                    if (library.state.value is HarnessState.Idle || library.state.value is HarnessState.Failed) {
                        library.send(HarnessIntent.Internal.Start)
                    }
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { library.send(HarnessIntent.Internal.Suspended) }
                }
            }
        }
    }
}
