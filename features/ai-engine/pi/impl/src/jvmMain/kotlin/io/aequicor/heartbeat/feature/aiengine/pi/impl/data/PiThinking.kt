package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Pi thinking levels in its native order (`pi-ai` `getSupportedThinkingLevels`). */
internal val PiThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/** Heartbeat level of a model whose thinking is only switched on or off; see [piThinkingLevels]. */
internal const val PI_THINKING_ON = "on"

/** Thinking levels a model may be sent: Pi's own and [PI_THINKING_ON]. */
internal val PiAcceptedThinkingLevels = PiThinkingLevels + PI_THINKING_ON

/**
 * Thinking levels a Pi model advertises: none for non-reasoning models; a level mapped to null is unsupported,
 * `xhigh` and `max` exist only when the model maps them explicitly. A model whose compat only switches thinking
 * (`enable_thinking` without `reasoning_effort`) gets `off` and [PI_THINKING_ON]: Pi would list every level, but
 * sends all of them except `off` identically.
 */
internal fun JsonObject.piThinkingLevels(): List<String> {
    if ((this["reasoning"] as? JsonPrimitive)?.booleanOrNull != true) return emptyList()
    if (isThinkingSwitch()) return listOf("off", PI_THINKING_ON)
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

/** Pi level for an advertised [level]: [PI_THINKING_ON] enables thinking at Pi's default level. */
internal fun piThinkingLevel(level: String): String = if (level == PI_THINKING_ON) PI_DEFAULT_THINKING else level

private fun JsonObject.isThinkingSwitch(): Boolean {
    val compat = this["compat"] as? JsonObject ?: return false
    val format = (compat["thinkingFormat"] as? JsonPrimitive)?.content
    val hasEffort = (compat["supportsReasoningEffort"] as? JsonPrimitive)?.booleanOrNull == true
    return format in SwitchThinkingFormats && !hasEffort
}

/** Pi compat formats that send only an on/off switch unless `supportsReasoningEffort` adds an effort. */
private val SwitchThinkingFormats = setOf("qwen", "qwen-chat-template")

private const val PI_DEFAULT_THINKING = "medium"
