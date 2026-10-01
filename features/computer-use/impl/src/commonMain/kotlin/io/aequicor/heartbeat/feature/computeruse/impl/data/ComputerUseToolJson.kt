package io.aequicor.heartbeat.feature.computeruse.impl.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

// Typed reads of model-supplied tool arguments; a missing or mistyped value is null, never a default.

internal fun JsonObject.hasAny(keys: Set<String>): Boolean = keys.any { it in this }

internal fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.raw(name: String): String? = this[name]?.toString()
internal fun JsonObject.flag(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
internal fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
internal fun JsonObject.number(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
