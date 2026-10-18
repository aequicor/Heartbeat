package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

/** The two owned executors of a workflow, including their journals and capacity resources. */
internal data class WorkflowAgentPorts(val helpers: WorkflowHelperPorts, val organisms: WorkflowOrganismPorts)
