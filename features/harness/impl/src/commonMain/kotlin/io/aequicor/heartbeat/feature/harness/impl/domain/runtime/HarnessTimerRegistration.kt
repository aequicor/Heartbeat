package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration

/** Disposal affects future acquisitions; acquired callbacks remain owned by the runtime until they stop. */
internal class HarnessTimerRegistration(
    val callback: HarnessCallback<suspend () -> Unit>,
    private val onDisposed: () -> Unit,
) : ScriptRegistration {
    val disposed = CompletableDeferred<Unit>()

    override fun dispose() {
        callback.dispose()
        if (disposed.complete(Unit)) onDisposed()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitDelay(duration: Duration): Boolean {
        if (duration <= Duration.ZERO) return callback.isActive
        return select {
            disposed.onAwait { false }
            onTimeout(duration) { callback.isActive }
        }
    }
}
