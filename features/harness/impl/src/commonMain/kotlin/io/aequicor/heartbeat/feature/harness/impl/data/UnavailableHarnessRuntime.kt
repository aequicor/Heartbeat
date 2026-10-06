package io.aequicor.heartbeat.feature.harness.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRuntimeControl

/** Never admits execution. Durable running journals still prevent claiming a removal barrier. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnavailableHarnessRuntime(private val runs: HarnessRunStorage) : HarnessRuntimeControl {
    override val isAvailable: Boolean = false
    override suspend fun activate(request: HarnessActivationRequest): Boolean = false
    override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean = true
    override suspend fun remove(harness: Harness): Boolean =
        runs.load().none { it.harness == harness.id && it.status == WorkflowStatus.Running }
}
