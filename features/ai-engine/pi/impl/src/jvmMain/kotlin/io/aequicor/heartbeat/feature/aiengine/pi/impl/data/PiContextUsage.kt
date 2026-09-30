package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/** Last native request only; Pi's contextUsage adds character estimates and is deliberately not used. */
internal fun piContextUsage(message: JsonObject, model: JsonObject?, now: Instant): ContextUsage? {
    if (message.string("role") != "assistant" || message.string("stopReason") in listOf("error", "aborted")) {
        return null
    }
    if (model == null || message.string("model") != model.string("id") ||
        message.string("provider") != model.string("provider")
    ) {
        return null
    }
    val capacity = model.number("contextWindow")?.takeIf { it > 0 } ?: return null
    val usage = message["usage"] as? JsonObject ?: return null
    val tokens = usage.number("totalTokens")?.takeIf { it > 0 } ?: run {
        val counts = listOf("input", "output", "cacheRead", "cacheWrite").map {
            usage.number(it)?.takeIf { count -> count >= 0 } ?: return null
        }
        counts.fold(0L) { sum, count -> if (Long.MAX_VALUE - sum < count) return null else sum + count }
    }
    return ContextUsage(tokens, capacity, Observation(now, isStale = false))
}

private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
