package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Preserves Anthropic cache tokens before Koog 1.3's streaming DTO discards them. Never retains response text. */
internal class KoogUsageCapture {
    private val log = Log.tag("KoogUsageCapture")
    private val gate = MutableStateFlow(false)
    private var input: Long? = null
    private var output: Long? = null
    private var hasFinalUsage = false

    val total: Long?
        get() = if (gate.value && hasFinalUsage) nativeTokenSum(input, output) else null

    fun enable(enabled: Boolean) {
        log.d { "Native usage capture enabled=$enabled" }
        gate.value = enabled
        if (!enabled) reset()
    }

    fun reset() {
        input = null
        output = null
        hasFinalUsage = false
    }

    fun anthropic(data: String) {
        if (!gate.value) return
        val event = try {
            Json.parseToJsonElement(data) as? JsonObject ?: return
        } catch (e: IllegalArgumentException) {
            log.w(e.sanitized()) { "Native usage event could not be decoded" }
            return
        }
        when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
            "message_start" -> {
                val usage = (event["message"] as? JsonObject)?.get("usage") as? JsonObject
                input = usage?.let {
                    nativeTokenSum(
                        nativeTokenSum(it.count("input_tokens"), it.cacheCount("cache_creation_input_tokens")),
                        it.cacheCount("cache_read_input_tokens"),
                    )
                }
                output = usage?.count("output_tokens")
            }

            "message_delta" -> {
                output = (event["usage"] as? JsonObject)?.count("output_tokens")
                hasFinalUsage = output != null
            }
        }
    }
}

private fun JsonObject.count(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

private fun JsonObject.cacheCount(key: String): Long? = if (containsKey(key)) count(key) else 0L

/** Adds native counters without treating missing, negative, or overflowing data as zero. */
internal fun nativeTokenSum(first: Long?, second: Long?): Long? {
    if (first == null || second == null) return null
    if (first < 0 || second < 0 || first > Long.MAX_VALUE - second) return null
    return first + second
}
