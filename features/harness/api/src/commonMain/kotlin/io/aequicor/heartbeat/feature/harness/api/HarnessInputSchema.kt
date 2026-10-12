package io.aequicor.heartbeat.feature.harness.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Checks the v1 schema subset. An empty root describes an object with no declared input properties. */
public fun isHarnessInputSchema(schema: JsonObject): Boolean =
    schema.isEmpty() || (schema.type() == "object" && schema.isSupportedSchema())

private fun JsonObject.isSupportedSchema(): Boolean {
    if (keys.any { it !in setOf("type", "properties", "required", "enum") }) return false
    return when (val type = type()) {
        "object" -> isObjectSchema()
        "string", "number", "boolean" -> isScalarSchema(type)
        else -> false
    }
}

private fun JsonObject.isScalarSchema(type: String): Boolean {
    if ("properties" in this || "required" in this) return false
    val values = get("enum") ?: return true
    return values is JsonArray && values.isNotEmpty() && values.distinct().size == values.size && values.all {
        it is JsonPrimitive && it.matchesSchemaType(type)
    }
}

private fun JsonPrimitive.matchesSchemaType(type: String): Boolean = when (type) {
    "string" -> isString
    "number" -> !isString && doubleOrNull?.isFinite() == true
    else -> !isString && booleanOrNull != null
}

private fun JsonObject.isObjectSchema(): Boolean {
    if ("enum" in this) return false
    val properties = get("properties") ?: JsonObject(emptyMap())
    val required = get("required") ?: JsonArray(emptyList())
    if (properties !is JsonObject || required !is JsonArray) return false
    if (properties.any {
            it.key.isBlank() || it.value !is JsonObject || !(it.value as JsonObject).isSupportedSchema()
        }
    ) {
        return false
    }
    val names = required.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
    return names.distinct().size == names.size && names.all { it != null && it in properties }
}

private fun JsonObject.type(): String? = (get("type") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
