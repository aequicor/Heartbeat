package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus

/** Pinned workflow barrier, separate from unload/replacement of a script or workflow definition. */
internal fun interface HarnessWorkflowLifecycle {
    suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean
}

/** Conservative fallback for hosts without an execution driver; never acknowledges live persistent work. */
internal class UndrivenWorkflowLifecycle(private val runs: HarnessRunStorage) : HarnessWorkflowLifecycle {
    override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean =
        effect.harnesses.isEmpty() || runs.load().none {
            it.harness in effect.harnesses && it.status == WorkflowStatus.Running
        }
}

/** Unique profile lifetime; persisted routing generations from another profile cannot evade today's stop fence. */
internal data class WorkflowLibraryEpoch(val value: String)
