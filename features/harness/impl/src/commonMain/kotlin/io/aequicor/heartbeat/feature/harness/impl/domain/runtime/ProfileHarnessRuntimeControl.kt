package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRemovalResult
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRuntimeControl
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnLifecycle

/**
 * Library adapter for executable code. No workflow driver or durable wake is admitted by this implementation.
 * Existing Running journals still forbid acknowledging a complete pause/stop/removal: a later driver must
 * supply that barrier. Code fences always precede storage reads and are retained across an uncertain read.
 */
internal class ProfileHarnessRuntimeControl(
    private val runtime: HarnessRuntime,
    private val runs: HarnessRunStorage,
    private val spawns: HarnessSpawnLifecycle,
) : HarnessRuntimeControl {
    override val isAvailable: Boolean get() = runtime.isAvailable

    override suspend fun activate(request: HarnessActivationRequest): Boolean = runtime.activate(request)

    override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean {
        val isCodeDrained = runtime.deactivate(effect)
        // A callback still in flight must not delay revoking its independently owned native helpers.
        val areHelpersDrained = spawns.deactivate(effect)
        val isQuiescent = isCodeDrained && areHelpersDrained && (
            effect.harnesses.isEmpty() || runs.load().none {
                it.harness in effect.harnesses && it.status == WorkflowStatus.Running
            }
        )
        return isQuiescent && runtime.removeObsoleteCache(effect.items)
    }

    override suspend fun remove(effect: HarnessEffect.Remove): HarnessRemovalResult {
        if (runtime.revokeHarness(effect) == HarnessRemovalResult.Obsolete) return HarnessRemovalResult.Obsolete
        val generation = runtime.removalGeneration(effect) ?: return HarnessRemovalResult.Obsolete
        val areHelpersDrained = spawns.deactivate(
            HarnessEffect.Deactivate(emptyList(), true, setOf(effect.harness.id), generation),
        )
        val status = runtime.removalStatus(effect)
        if (status != HarnessRemovalResult.Ready) return status
        if (!areHelpersDrained) return HarnessRemovalResult.Retry
        val isRunning = runs.load().any {
            it.harness == effect.harness.id && it.status == WorkflowStatus.Running
        }
        return when (val latest = runtime.removalStatus(effect)) {
            HarnessRemovalResult.Ready -> if (isRunning) HarnessRemovalResult.Retry else runtime.removeCached(effect)
            HarnessRemovalResult.Retry, HarnessRemovalResult.Obsolete -> latest
        }
    }
}
