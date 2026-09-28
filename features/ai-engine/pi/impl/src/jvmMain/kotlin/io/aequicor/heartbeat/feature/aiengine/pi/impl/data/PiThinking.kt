package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Pi thinking levels in its native order (`pi-ai` `getSupportedThinkingLevels`). */
internal val PiThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/**
 * Thinking levels a Pi model advertises: none for non-reasoning models; a level mapped to null is unsupported,
 * `xhigh` and `max` exist only when the model maps them explicitly.
 */
internal fun JsonObject.piThinkingLevels(): List<String> {
    if ((this["reasoning"] as? JsonPrimitive)?.booleanOrNull != true) return emptyList()
    val map = this["thinkingLevelMap"] as? JsonObject
    return PiThinkingLevels.filter { level ->
        val mapped = map?.get(level)
        when {
            mapped is JsonNull -> false
            level == "xhigh" || level == "max" -> mapped != null
            else -> true
        }
    }
}
