package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Synchronous atomic registration, sealed before execution. Catching a duplicate error cannot repair it. */
internal class WorkflowDefinitionCapture : WorkflowRegistration {
    private val state = MutableStateFlow(Snapshot())
    private val log = Log.tag("HarnessWorkflow")

    override fun register(definition: WorkflowDefinition) {
        val before = state.getAndUpdate { old ->
            when {
                old.isSealed -> old
                old.definition != null -> old.copy(isInvalid = true)
                else -> old.copy(definition = definition)
            }
        }
        log.v { "Workflow definition registered" }
        check(!before.isSealed && before.definition == null) { "Register exactly one workflow during evaluation" }
    }

    fun seal(): WorkflowDefinition? {
        val before = state.getAndUpdate { it.copy(isSealed = true) }
        log.v { "Workflow definition sealed" }
        return before.takeIf { !it.isInvalid && !it.isSealed }?.definition
    }

    private data class Snapshot(
        val definition: WorkflowDefinition? = null,
        val isSealed: Boolean = false,
        val isInvalid: Boolean = false,
    ) {
        override fun toString(): String = "WorkflowDefinitionCapture(***)"
    }
}
