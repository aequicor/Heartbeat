package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRemovalResult
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRuntimeControl
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnLifecycle
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessWorkflowLifecycle
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.UndrivenWorkflowLifecycle

/**
 * Library adapter for executable code, script helpers and pinned workflows. Every independent owner is revoked
 * before checking its drain barrier. Code fences precede storage reads and survive uncertain outcomes.
 */
internal class ProfileHarnessRuntimeControl(
    private val runtime: HarnessRuntime,
    runs: HarnessRunStorage,
    private val workflows: HarnessWorkflowLifecycle = UndrivenWorkflowLifecycle(runs),
    private val spawns: HarnessSpawnLifecycle,
) : HarnessRuntimeControl {
    override val isAvailable: Boolean get() = runtime.isAvailable

    override suspend fun activate(request: HarnessActivationRequest): Boolean = runtime.activate(request)

    override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean {
        val isCodeDrained = runtime.deactivate(effect)
        // A callback still in flight must not delay revoking its independently owned native helpers.
        val areHelpersDrained = spawns.deactivate(effect)
        val areWorkflowsDrained = workflows.deactivate(effect)
        val isQuiescent = isCodeDrained && areHelpersDrained && areWorkflowsDrained
        return isQuiescent && runtime.removeObsoleteCache(effect.items)
    }

    override suspend fun remove(effect: HarnessEffect.Remove): HarnessRemovalResult {
        if (runtime.revokeHarness(effect) == HarnessRemovalResult.Obsolete) return HarnessRemovalResult.Obsolete
        val generation = runtime.removalGeneration(effect) ?: return HarnessRemovalResult.Obsolete
        val areHelpersDrained = spawns.deactivate(
            HarnessEffect.Deactivate(emptyList(), true, setOf(effect.harness.id), generation),
        )
        val areWorkflowsDrained = workflows.deactivate(
            HarnessEffect.Deactivate(emptyList(), true, setOf(effect.harness.id), generation),
        )
        val status = runtime.removalStatus(effect)
        if (status != HarnessRemovalResult.Ready) return status
        if (!areHelpersDrained || !areWorkflowsDrained) return HarnessRemovalResult.Retry
        return when (val latest = runtime.removalStatus(effect)) {
            HarnessRemovalResult.Ready -> runtime.removeCached(effect)
            HarnessRemovalResult.Retry, HarnessRemovalResult.Obsolete -> latest
        }
    }
}
