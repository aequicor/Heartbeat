package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageWindow
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Main-request occupancy, never result-level accumulated usage or a subagent's request. */
internal class ClaudeContextUsage : SessionContextUsage {
    private val log = Log.tag("ClaudeContextUsage")
    private val mutableState = MutableStateFlow<ContextUsage?>(null)
    override val state = mutableState.asStateFlow()
    private val lock = Any()
    private var model: String? = null
    private var tokens: Long? = null
    private var capacity: Long? = null

    fun receive(message: JsonObject): Unit = synchronized(lock) {
        when (message.text("type")) {
            "system" -> when (message.text("subtype")) {
                "compact_boundary" -> clear()
                "init" -> message.text("model")?.let(::selectModel)
                "status" -> if (message.text("status") == "compacting") clear()
                else -> Unit
            }

            "conversation_reset" -> clear()

            "assistant" -> assistant(message)

            "result" -> {
                val models = message["modelUsage"] as? JsonObject
                val active = model?.let { models?.get(it) as? JsonObject }
                capacity = active?.positiveLong("contextWindow")
                publish()
            }

            else -> Unit
        }
    }

    private fun assistant(message: JsonObject) {
        if (message["parent_tool_use_id"] != null && message["parent_tool_use_id"] != JsonNull) return
        val body = message["message"] as? JsonObject ?: return
        val actualModel = body.text("model") ?: return clear()
        selectModel(actualModel)
        tokens = claudeRequestTokens(body["usage"] as? JsonObject)
        publish()
    }

    fun clear() = synchronized(lock) {
        log.d { "Claude context observation cleared" }
        tokens = null
        capacity = null
        model = null
        mutableState.value = null
    }

    private fun selectModel(value: String) {
        if (value != model) {
            clear()
            model = value
        }
    }

    private fun publish() {
        log.d { "Claude context observation updated" }
        val used = tokens
        val limit = capacity
        mutableState.value = if (used != null && limit != null) {
            ContextUsage(used, limit, Observation(Clock.System.now(), isStale = false))
        } else {
            null
        }
    }
}

/** CLI rate-limit events have fractional utilization; refresh never generates a paid model turn. */
internal class ClaudeProviderUsage(private val validate: suspend () -> Boolean) : ReportsProviderUsage {
    private val log = Log.tag("ClaudeUsage")
    private val lock = Any()
    private val mutableState = MutableStateFlow(ProviderUsageSnapshot())
    override val state = mutableState.asStateFlow()

    override suspend fun refresh(): ProviderUsageSnapshot {
        try {
            if (!validate()) clear()
        } catch (e: EngineException) {
            failed(e.failure)
            throw e
        }
        return state.value
    }

    fun receive(message: JsonObject): Unit = synchronized(lock) {
        if (message.text("type") != "rate_limit_event") return
        val value = message["rate_limit_info"] as? JsonObject ?: return
        val id = value.text("rateLimitType")?.takeIf { it.isNotBlank() } ?: return
        val fraction = (value["utilization"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0 } ?: return
        val seconds = (value["resetsAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        val window = ProviderUsageWindow(
            id = id,
            title = id,
            usedPercent = fraction * PERCENT,
            resetsAt = seconds?.let { Instant.fromEpochSeconds(it) },
            windowDurationMinutes = when {
                id == "five_hour" -> FIVE_HOURS
                id.startsWith("seven_day") -> SEVEN_DAYS
                else -> null
            },
        )
        mutableState.value = state.value.copy(
            windows = state.value.windows.filterNot { it.id == id } + window,
            observation = Observation(Clock.System.now(), isStale = false),
        )
        log.d { "Claude quota observation updated" }
    }

    fun clear() = synchronized(lock) {
        log.d { "Claude quota observation cleared" }
        mutableState.value = ProviderUsageSnapshot()
    }

    private fun failed(failure: EngineFailure) = synchronized(lock) {
        if (failure is EngineFailure.Authentication || failure is EngineFailure.Access ||
            failure is EngineFailure.Lifecycle
        ) {
            clear()
        } else {
            log.d { "Claude quota observation retained after refresh failure" }
            mutableState.value = state.value.copy(observation = state.value.observation.copy(isStale = true))
        }
    }
}

private fun claudeRequestTokens(usage: JsonObject?): Long? {
    if (usage == null) return null
    val input = usage.nonnegativeLong("input_tokens") ?: return null
    val output = usage.nonnegativeLong("output_tokens") ?: return null
    val read = if ("cache_read_input_tokens" in usage) {
        usage.nonnegativeLong("cache_read_input_tokens") ?: return null
    } else {
        0
    }
    val created = if ("cache_creation_input_tokens" in usage) {
        usage.nonnegativeLong("cache_creation_input_tokens") ?: return null
    } else {
        0
    }
    return listOf(input, output, read, created).fold(0L) { total, value ->
        if (value > Long.MAX_VALUE - total) return null
        total + value
    }
}

private fun JsonObject.nonnegativeLong(key: String): Long? =
    (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

private fun JsonObject.positiveLong(key: String): Long? = nonnegativeLong(key)?.takeIf { it > 0 }

private const val PERCENT = 100.0
private const val FIVE_HOURS = 300L
private const val SEVEN_DAYS = 10080L
