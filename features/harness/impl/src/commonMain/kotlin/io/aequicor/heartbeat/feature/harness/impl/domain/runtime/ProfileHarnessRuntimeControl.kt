package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRemovalResult
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRuntimeControl

/**
 * Library adapter for executable code. No workflow driver or durable wake is admitted by this implementation.
 * Existing Running journals still forbid acknowledging a complete pause/stop/removal: a later driver must
 * supply that barrier. Code fences always precede storage reads and are retained across an uncertain read.
 */
internal class ProfileHarnessRuntimeControl(private val runtime: HarnessRuntime, private val runs: HarnessRunStorage) :
    HarnessRuntimeControl {
    override val isAvailable: Boolean get() = runtime.isAvailable

    override suspend fun activate(request: HarnessActivationRequest): Boolean = runtime.activate(request)

    override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean {
        val isCodeDrained = runtime.deactivate(effect)
        val isQuiescent = isCodeDrained && (
            effect.harnesses.isEmpty() || runs.load().none {
                it.harness in effect.harnesses && it.status == WorkflowStatus.Running
            }
        )
        return isQuiescent && runtime.removeObsoleteCache(effect.items)
    }

    override suspend fun remove(effect: HarnessEffect.Remove): HarnessRemovalResult =
        when (val status = runtime.revokeHarness(effect)) {
            HarnessRemovalResult.Ready -> if (runs.load().any {
                    it.harness == effect.harness.id && it.status == WorkflowStatus.Running
                }
            ) {
                if (runtime.removalStatus(effect) == HarnessRemovalResult.Obsolete) {
                    HarnessRemovalResult.Obsolete
                } else {
                    HarnessRemovalResult.Retry
                }
            } else {
                runtime.removeCached(effect)
            }

            HarnessRemovalResult.Retry, HarnessRemovalResult.Obsolete -> status
        }
}
