package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCredits
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageWindow
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Only the last native request describes occupied context; lifetime totals are never used. */
internal class CodexContextUsage : SessionContextUsage {
    suspend fun event(method: String?, params: JsonObject, isEnabled: suspend () -> Boolean): Boolean {
        when (method) {
            "thread/tokenUsage/updated" -> if (isEnabled()) receive(params)

            "thread/compacted" -> clear()

            else -> {
                if (method == "item/started" || method == "item/completed") {
                    val item = params["item"] as? JsonObject
                    if (item?.text("type") == "contextCompaction") clear()
                }
                return false
            }
        }
        return true
    }

    private val log = Log.tag("CodexContextUsage")
    private val mutableState = MutableStateFlow<ContextUsage?>(null)
    override val state = mutableState.asStateFlow()

    fun receive(params: JsonObject) {
        val usage = params["tokenUsage"] as? JsonObject
        val last = usage?.get("last") as? JsonObject
        val tokens = (last?.get("totalTokens") as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        val capacity = (usage?.get("modelContextWindow") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        log.d { "Codex context observation updated" }
        mutableState.value = if (tokens != null && capacity != null) {
            ContextUsage(tokens, capacity, Observation(Clock.System.now(), isStale = false))
        } else {
            null
        }
    }

    fun clear() {
        log.d { "Codex context observation cleared" }
        mutableState.value = null
    }
}

/** Account-owned quota snapshots; rolling notifications are sparse patches, not full replacements. */
internal class CodexProviderUsage(
    private val dispatcher: CoroutineDispatcher,
    private val read: suspend () -> JsonObject?,
) : ReportsProviderUsage {
    private val log = Log.tag("CodexUsage")
    private val buckets = mutableMapOf<String, JsonObject>()
    private val mutableState = MutableStateFlow(ProviderUsageSnapshot())
    override val state = mutableState.asStateFlow()

    override suspend fun refresh(): ProviderUsageSnapshot = withContext(dispatcher) {
        val result = try {
            read()
        } catch (e: EngineException) {
            failed(e.failure)
            throw e
        }
        if (result == null) {
            clear()
        } else {
            buckets.clear()
            val multiple = result["rateLimitsByLimitId"] as? JsonObject
            if (!multiple.isNullOrEmpty()) {
                multiple.forEach { (id, value) -> (value as? JsonObject)?.let { buckets[id] = it } }
            } else {
                (result["rateLimits"] as? JsonObject)?.let { buckets[it.text("limitId") ?: DEFAULT_BUCKET] = it }
            }
            publish()
        }
        state.value
    }

    fun receive(params: JsonObject) {
        val value = params["rateLimits"] as? JsonObject ?: return
        val id = value.text("limitId") ?: DEFAULT_BUCKET
        buckets[id] = mergeUsageObject(buckets[id], value)
        publish()
    }

    fun clear() {
        log.d { "Codex quota observation cleared" }
        buckets.clear()
        mutableState.value = ProviderUsageSnapshot()
    }

    private fun failed(failure: EngineFailure) {
        if (failure is EngineFailure.Authentication || failure is EngineFailure.Access ||
            failure is EngineFailure.Lifecycle
        ) {
            clear()
        } else {
            log.d { "Codex quota observation retained after refresh failure" }
            mutableState.value = state.value.copy(observation = state.value.observation.copy(isStale = true))
        }
    }

    private fun publish() {
        val windows = buckets.flatMap { (id, bucket) ->
            listOfNotNull(window(id, bucket, "primary"), window(id, bucket, "secondary"))
        }
        mutableState.value = ProviderUsageSnapshot(
            windows = windows,
            credits = buckets.values.firstNotNullOfOrNull { credits(it["credits"] as? JsonObject) },
            planName = buckets.values.firstNotNullOfOrNull { it.text("planType") },
            observation = Observation(Clock.System.now(), isStale = false),
        )
        log.d { "Codex quota observation updated windows=${windows.size}" }
    }
}

private fun window(id: String, bucket: JsonObject, key: String): ProviderUsageWindow? {
    val value = bucket[key] as? JsonObject ?: return null
    val used = (value["usedPercent"] as? JsonPrimitive)?.doubleOrNull
        ?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val reset = (value["resetsAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    return ProviderUsageWindow(
        id = "$id:$key",
        title = bucket.text("limitName") ?: bucket.text("limitId") ?: "Codex",
        usedPercent = used,
        resetsAt = reset?.let { Instant.fromEpochSeconds(it) },
        windowDurationMinutes = (value["windowDurationMins"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
    )
}

private fun credits(value: JsonObject?): ProviderUsageCredits? {
    val remaining = (value?.get("balance") as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
    val isUnlimited = (value?.get("unlimited") as? JsonPrimitive)?.booleanOrNull == true
    return if (remaining != null || isUnlimited) {
        ProviderUsageCredits(
            remaining = remaining,
            isUnlimited = isUnlimited,
        )
    } else {
        null
    }
}

private fun mergeUsageObject(previous: JsonObject?, patch: JsonObject): JsonObject = JsonObject(
    previous.orEmpty() + patch.mapNotNull { (key, value) ->
        if (value is kotlinx.serialization.json.JsonNull) {
            null
        } else {
            key to if (value is JsonObject) mergeUsageObject(previous?.get(key) as? JsonObject, value) else value
        }
    }.toMap(),
)

private const val DEFAULT_BUCKET = "codex"
