package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal val HARNESS_CONTENT_SPECS = listOf(
    AgentToolSpec(HarnessTools.CONTEXT, "Read all active harness instructions and skill/template indexes", schema()),
    AgentToolSpec(
        HarnessTools.SKILL_LOAD,
        "Read an enabled harness skill before the task it applies to; name is harness/name or an unambiguous name",
        schema(hasName = true),
    ),
    AgentToolSpec(
        HarnessTools.PROMPT_GET,
        "Render an enabled harness template by harness/name with its exact string arguments",
        schema(hasName = true, hasArguments = true),
    ),
)

private fun schema(hasName: Boolean = false, hasArguments: Boolean = false): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        if (hasName) putJsonObject("name") { put("type", "string") }
        if (hasArguments) {
            putJsonObject("args") {
                put("type", "object")
                putJsonObject("additionalProperties") { put("type", "string") }
            }
        }
    }
    put("required", buildJsonArray { if (hasName) add(JsonPrimitive("name")) })
}
