package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Library-independent declarations shared by all adapters. */
internal enum class ToolParameterType(val schema: String) { String("string"), Integer("integer"), Boolean("boolean") }

internal data class ToolParameterDescriptor(val name: String, val description: String, val type: ToolParameterType)

internal data class ToolDescriptor(
    val name: String,
    val description: String,
    val requiredParameters: List<ToolParameterDescriptor>,
    val optionalParameters: List<ToolParameterDescriptor>,
) {
    fun specification(action: AgentToolAction): AgentToolSpec = AgentToolSpec(
        name,
        description,
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    (requiredParameters + optionalParameters).forEach { parameter ->
                        put(
                            parameter.name,
                            buildJsonObject {
                                put("type", parameter.type.schema)
                                put("description", parameter.description)
                            },
                        )
                    }
                },
            )
            put("required", JsonArray(requiredParameters.map { JsonPrimitive(it.name) }))
            put("additionalProperties", false)
        },
        action,
    )
}

internal interface CodingTool {
    val descriptor: ToolDescriptor
    val isMutating: Boolean get() = false
    fun target(args: JsonObject): String = descriptor.description
    fun details(args: JsonObject): String? = null
    suspend fun run(args: JsonObject): AgentToolResult
}

internal fun JsonObject.argText(name: String): String = (get(name) as? JsonPrimitive)?.content.orEmpty()
internal fun JsonObject.argInt(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.argFlag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull
