package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessCommandRuns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Running commands per harness across its activations, so a replaced script cannot double the limit. */
@SingleIn(ProfileScope::class)
@Inject
internal class HarnessCommandSlots : HarnessCommandRuns {
    private val log = Log.tag("HarnessCommandSlots")
    private val running = MutableStateFlow<Map<HarnessId, Int>>(emptyMap())

    override fun tryAcquire(harness: HarnessId): Boolean {
        while (true) {
            val previous = running.value
            val count = previous[harness] ?: 0
            if (count >= HarnessLimits.COMMAND_RUNS) return false
            if (running.compareAndSet(previous, previous + (harness to count + 1))) {
                log.v { "running: ${count + 1} commands of a harness" }
                return true
            }
        }
    }

    override fun release(harness: HarnessId) {
        running.update { current ->
            val count = (current[harness] ?: 0) - 1
            if (count > 0) current + (harness to count) else current - harness
        }
        log.v { "running: a harness command slot released" }
    }
}
