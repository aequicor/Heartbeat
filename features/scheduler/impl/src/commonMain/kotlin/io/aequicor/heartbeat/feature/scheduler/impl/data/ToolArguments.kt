package io.aequicor.heartbeat.feature.scheduler.impl.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A trimmed string argument, or null when absent, blank or not a string. */
internal fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)

/** A whole-number argument (also given as a numeric string), or null. */
internal fun JsonObject.whole(name: String): Long? {
    val value = this[name] as? JsonPrimitive ?: return null
    return if (value.isString) value.content.trim().toLongOrNull() else value.longOrNull
}

/** String items of an array argument; null when absent, an empty list for a non-array. */
internal fun JsonObject.texts(name: String): List<String>? {
    val value = this[name] ?: return null
    return (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}

internal fun JsonObjectBuilder.stringProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

internal fun JsonObjectBuilder.integerProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", "integer")
        put("description", description)
    }
}

internal fun JsonObjectBuilder.stringArrayProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }
}

internal fun JsonObjectBuilder.enumProperty(name: String, description: String, vararg values: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
}

internal fun JsonObjectBuilder.required(vararg names: String) {
    put("required", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
}
