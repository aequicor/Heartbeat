package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage

/** No workflow-owned events in session/scheduler-only fixtures. */
internal object EmptyHarnessEventRuns : HarnessRunStorage {
    override suspend fun load(): List<WorkflowRun> = emptyList()
    override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean = error("Read-only event fixture")
}
