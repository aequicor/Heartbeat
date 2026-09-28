package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal val acpJson = Json { ignoreUnknownKeys = true }

internal fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw AcpException.Protocol()

internal fun JsonObject.obj(name: String): JsonObject = get(name) as? JsonObject ?: throw AcpException.Protocol()

internal fun JsonObject.number(name: String): Int =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw AcpException.Protocol()

internal fun fields(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*values))

/** Sanitizes untrusted transport/decoder/callback exceptions before they reach the logging facade. */
internal class AcpDiagnostic(error: Exception) : Exception("ACP failure: ${error::class.simpleName ?: "Exception"}")
