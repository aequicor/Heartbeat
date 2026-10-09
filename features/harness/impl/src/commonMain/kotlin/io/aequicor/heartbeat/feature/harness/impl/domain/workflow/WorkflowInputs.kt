package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.isHarnessInputSchema
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Exact declared-property validation; unknown fields, coercion, nonfinite numbers and unbounded input fail. */
internal fun isWorkflowInput(schema: JsonObject, input: JsonObject): Boolean =
    input.toString().length <= HarnessLimits.SOURCE_CHARS && isHarnessInputSchema(schema) && matchesInput(schema, input)

private fun matchesInput(schema: JsonObject, input: JsonElement): Boolean {
    val type = (schema["type"] as? JsonPrimitive)?.content ?: "object"
    if (type == "object") {
        val value = input as? JsonObject ?: return false
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
        return value.keys.all { it in properties } && required.all { it in value } &&
            value.all { (key, item) -> matchesInput(properties.getValue(key) as JsonObject, item) }
    }
    val value = input as? JsonPrimitive ?: return false
    val isTyped = when (type) {
        "string" -> value.isString
        "number" -> !value.isString && value.doubleOrNull?.isFinite() == true
        "boolean" -> !value.isString && value.booleanOrNull != null
        else -> false
    }
    return isTyped && (schema["enum"] as? JsonArray)?.let { value in it } != false
}

/** Pins only enabled non-code content. Later library edits cannot silently change a replay's helper prompt. */
internal fun pinWorkflow(harness: Harness, item: HarnessItem.Workflow, digest: (String) -> String): PinnedWorkflow =
    PinnedWorkflow(
        item.source, digest(item.source), harness.revision,
        harness.items.filter { it.isEnabled }.mapNotNull {
            val text = when (it) {
                is HarnessItem.Skill -> it.body
                is HarnessItem.Template -> it.body
                is HarnessItem.Instruction -> it.text
                is HarnessItem.Script, is HarnessItem.Workflow -> null
            }
            text?.let { text -> it.name to text }
        }.toMap(),
    )
