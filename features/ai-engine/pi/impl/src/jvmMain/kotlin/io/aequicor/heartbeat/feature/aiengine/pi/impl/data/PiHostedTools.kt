package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Extension declarations and guidance; an instructions-only context needs no bridge credentials. */
internal data class PiHostedTools(
    val endpoint: AgentToolBridgeEndpoint?,
    val specifications: List<AgentToolSpec>,
    val instructions: String,
) {
    fun applyEnvironment(environment: MutableMap<String, String>) {
        endpoint?.let {
            environment["HEARTBEAT_AGENT_TOOLS_URL"] = it.url
            environment["HEARTBEAT_AGENT_TOOLS_TOKEN"] = it.token
        }
        environment["HEARTBEAT_AGENT_TOOL_SPECS"] = schemas()
        environment["HEARTBEAT_AGENT_TOOL_INSTRUCTIONS"] = instructions
    }

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
