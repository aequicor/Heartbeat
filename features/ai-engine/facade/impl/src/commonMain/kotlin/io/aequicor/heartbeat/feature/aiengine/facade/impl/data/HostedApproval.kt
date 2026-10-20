package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import kotlinx.serialization.json.JsonObject

/** One captured operation; no argument, review reason or presentation text is logged. */
internal data class HostedApproval(
    val owner: AgentToolContribution,
    val spec: AgentToolSpec,
    val arguments: JsonObject,
    val approval: AgentToolApproval,
) {
    val argumentsSnapshot: String = arguments.toString()

    override fun toString(): String = "HostedApproval"
}
