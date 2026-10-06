package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The common dispatcher owns permission checks; the legacy Koog mutating gate must not run twice. */
internal fun koogHostedTools(
    specs: List<AgentToolSpec>,
    tools: ProfileAgentTools,
    context: AgentToolContext,
): List<KoogTool> = specs.map { spec ->
    object : KoogTool {
        override val descriptor = spec.koogDescriptor()
        override suspend fun run(args: JsonObject): KoogToolResult {
            val result = tools.execute(context, spec.name, args)
            return KoogToolResult(result.text, result.isError, images = result.images)
        }
    }
}

internal fun AgentToolSpec.koogDescriptor(): ToolDescriptor {
    val required = inputSchema.requiredFields()
    val parameters = inputSchema.parameters()
    return ToolDescriptor(
        name,
        description,
        parameters.filter { it.name in required },
        parameters.filter { it.name !in required },
    )
}

private fun JsonObject.requiredFields(): List<String> =
    (get("required") as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }

private fun JsonObject.parameters(): List<ToolParameterDescriptor> =
    (get("properties") as? JsonObject).orEmpty().map { (name, value) ->
        val schema = value as JsonObject
        ToolParameterDescriptor(name, schema.argText("description"), schema.parameterType())
    }

/** Preserves nested recipes, arrays, optional fields and dictionaries rather than flattening them to text. */
private fun JsonObject.parameterType(): ToolParameterType {
    val enum = get("enum") as? JsonArray
    val anyOf = get("anyOf") as? JsonArray
    return when {
        enum != null -> ToolParameterType.Enum(enum.map { (it as JsonPrimitive).content }.toTypedArray())

        anyOf != null -> ToolParameterType.AnyOf(
            anyOf.map { value ->
                ToolParameterDescriptor("", "", (value as JsonObject).parameterType())
            }.toTypedArray(),
        )

        else -> basicParameterType()
    }
}

private fun JsonObject.basicParameterType(): ToolParameterType = when (argText("type")) {
    "string" -> ToolParameterType.String

    "integer" -> ToolParameterType.Integer

    "number" -> ToolParameterType.Float

    "boolean" -> ToolParameterType.Boolean

    "null" -> ToolParameterType.Null

    "array" -> ToolParameterType.List(requireNotNull(get("items") as? JsonObject).parameterType())

    "object" -> ToolParameterType.Object(
        parameters(),
        requiredFields(),
        if (get("additionalProperties") is JsonObject) {
            true
        } else {
            (get("additionalProperties") as? JsonPrimitive)?.booleanOrNull
        },
        (get("additionalProperties") as? JsonObject)?.parameterType(),
    )

    else -> error("Unsupported hosted tool schema")
}
