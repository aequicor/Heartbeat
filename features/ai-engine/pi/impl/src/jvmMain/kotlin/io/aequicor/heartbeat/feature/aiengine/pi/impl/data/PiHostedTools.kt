package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Declarations supplied to the private bundled extension; credentials remain in its environment. */
internal data class PiHostedTools(
    val endpoint: AgentToolBridgeEndpoint,
    val specifications: List<AgentToolSpec>,
    val instructions: String,
) {
    fun schemas(): String = JsonArray(
        specifications.map { spec ->
            buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("inputSchema", spec.inputSchema)
            }
        },
    ).toString()
    override fun toString(): String = "PiHostedTools"
}
