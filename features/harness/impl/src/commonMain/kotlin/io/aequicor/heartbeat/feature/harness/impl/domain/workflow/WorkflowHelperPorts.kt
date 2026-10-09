package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBindings
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents

/** Helper-side ports of workflow execution: grant journal, slot resources, the helper service and bindings. */
internal data class WorkflowHelperPorts(
    val grants: WorkflowHelperJournal,
    val resources: WorkflowHelperResources,
    val helpers: HelperAgents,
    val bindings: HarnessHelperBindings,
)
