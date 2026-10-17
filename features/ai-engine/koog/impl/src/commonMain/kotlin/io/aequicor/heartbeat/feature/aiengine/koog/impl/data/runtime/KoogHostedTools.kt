package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val log = Log.tag("KoogHostedTools")

/**
 * The common dispatcher owns permission checks; the legacy Koog mutating gate must not run twice.
 * A tool whose schema Koog cannot describe is skipped and reported, so it never hides the other hosted tools.
 */
internal fun koogHostedTools(
    specs: List<AgentToolSpec>,
    tools: ProfileAgentTools,
    context: AgentToolContext,
): List<KoogTool> = specs.mapNotNull { spec ->
    val described = spec.describedOrNull() ?: return@mapNotNull null
    object : KoogTool {
        override val descriptor = described
        override suspend fun run(args: JsonObject): KoogToolResult = execute(context, args)

        override suspend fun run(args: JsonObject, call: ToolCallId): KoogToolResult =
            execute(context.copy(callId = call), args)

        private suspend fun execute(callContext: AgentToolContext, args: JsonObject): KoogToolResult {
            val result = tools.execute(callContext, spec.name, args)
            return KoogToolResult(result.text, result.isError, images = result.images)
        }
    }
}

private fun AgentToolSpec.describedOrNull(): ToolDescriptor? = try {
    koogDescriptor()
} catch (e: IllegalStateException) {
    log.w(e) { "Hosted tool $name has an unsupported schema and is not offered" }
    null
} catch (e: IllegalArgumentException) {
    log.w(e) { "Hosted tool $name has an unsupported schema and is not offered" }
    null
} catch (e: ClassCastException) {
    log.w(e) { "Hosted tool $name has an unsupported schema and is not offered" }
    null
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
    val const = get("const") as? JsonPrimitive
    // oneOf is declared by exact variants; Koog has no exclusivity, so the variants read as anyOf.
    val anyOf = (get("anyOf") ?: get("oneOf")) as? JsonArray
    return when {
        enum != null -> ToolParameterType.Enum(enum.map { (it as JsonPrimitive).content }.toTypedArray())

        const != null -> ToolParameterType.Enum(arrayOf(const.content))

        anyOf != null -> ToolParameterType.AnyOf(
            anyOf.map { value ->
                ToolParameterDescriptor("", "", (value as JsonObject).parameterType())
            }.toTypedArray(),
        )

        else -> basicParameterType()
    }
}

/** A schema without a type is free-form JSON (sample data); Koog has no "any", so it reads as an open object. */
private fun JsonObject.schemaType(): String = if ("type" in this) argText("type") else "object"

private fun JsonObject.basicParameterType(): ToolParameterType = when (schemaType()) {
    "string" -> ToolParameterType.String

    "integer" -> ToolParameterType.Integer

    "number" -> ToolParameterType.Float

    "boolean" -> ToolParameterType.Boolean

    "null" -> ToolParameterType.Null

    "array" -> ToolParameterType.List(requireNotNull(get("items") as? JsonObject).parameterType())

    "object" -> ToolParameterType.Object(
        parameters(),
        requiredFields(),
        additionalProperties(),
        (get("additionalProperties") as? JsonObject)?.parameterType(),
    )

    else -> error("Unsupported hosted tool schema")
}

/** Free-form JSON (no declared type) stays open: providers that close an object with an absent flag would reject it. */
private fun JsonObject.additionalProperties(): Boolean? {
    val declared = get("additionalProperties")
    return when {
        declared is JsonObject -> true
        declared is JsonPrimitive && declared.booleanOrNull != null -> declared.booleanOrNull
        "type" !in this -> true
        else -> null
    }
}
