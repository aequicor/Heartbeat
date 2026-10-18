package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest

/**
 * A permission request of one cell of the organism a workflow step owns, as the user sees it in the runs view. An
 * organism has no chat, so the runs view is the only place where its cells can ask. The organism itself is never
 * named: [cell] only lets the step's owner find the cell that asked, and [cellName] is what the user reads. Requests
 * are observed live and never stored; texts are never rendered by [toString].
 */
public data class OrganismPermission(
    val step: StepKey,
    val cell: String,
    val cellName: String,
    val request: PermissionRequest,
) {
    init {
        require(cell.isNotBlank()) { "A request belongs to a cell" }
    }

    override fun toString(): String = "OrganismPermission(step=${step.value}, ***)"
}
