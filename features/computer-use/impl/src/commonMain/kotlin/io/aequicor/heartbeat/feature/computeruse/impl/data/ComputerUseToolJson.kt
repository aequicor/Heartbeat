package io.aequicor.heartbeat.feature.computeruse.impl.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// Typed reads of model-supplied tool arguments; a missing or mistyped value is null, never a default.

internal fun JsonObject.hasAny(keys: Set<String>): Boolean = keys.any { it in this }

internal fun JsonObject.text(name: String): String? = primitive(name)?.contentOrNull
internal fun JsonObject.raw(name: String): String? = this[name]?.toString()
internal fun JsonObject.flag(name: String): Boolean? = primitive(name)?.booleanOrNull
internal fun JsonObject.int(name: String): Int? = primitive(name)?.intOrNull
internal fun JsonObject.number(name: String): Double? = primitive(name)?.doubleOrNull

/** An object or array where a value belongs is mistyped too: it reads as null instead of throwing. */
private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive
