package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

/**
 * Stream-json control request that initializes the CLI and returns its model catalog. It is one protocol frame, not
 * a model prompt: model discovery and the restricted admission baseline send exactly this and nothing else.
 */
internal const val CLAUDE_MODELS_INITIALIZE: String =
    """{"type":"control_request","request_id":"models","request":{"subtype":"initialize"}}"""

internal fun parseClaudeObject(line: String): JsonObject = try {
    Json.parseToJsonElement(line) as? JsonObject ?: protocolFailure()
} catch (e: SerializationException) {
    Log.tag("ClaudeProtocol").w(e.redacted()) { "Malformed CLI frame" }
    protocolFailure()
}

internal fun protocolFailure(): Nothing = throw EngineException(
    EngineFailure.Transport(TransportFailureReason.ProtocolViolation),
)

/** Never attach native messages or causes: JSON errors and process exceptions can contain user content. */
internal fun Throwable.redacted(): Throwable = IllegalStateException("Claude failure: ${javaClass.simpleName}")
