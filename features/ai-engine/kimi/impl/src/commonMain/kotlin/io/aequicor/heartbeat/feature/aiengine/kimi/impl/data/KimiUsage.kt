package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCredits
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageWindow
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Instant

internal class KimiContextUsage : SessionContextUsage {
    private val mutableState = MutableStateFlow<ContextUsage?>(null)
    override val state = mutableState.asStateFlow()

    @HighFrequency
    fun receive(update: JsonObject) {
        val usage = kimiContextUsage(update, Clock.System.now())
        log.v { "mutableState: ${mutableState.value.toLogString()} -> ${usage.toLogString()}" }
        mutableState.value = usage
    }

    fun clear() {
        log.i { "mutableState: ${mutableState.value.toLogString()} -> null" }
        mutableState.value = null
    }
}

internal class KimiProviderUsage(private val isEnabled: suspend () -> Boolean) : ReportsProviderUsage {
    private val mutableState = MutableStateFlow(ProviderUsageSnapshot())
    override val state = mutableState.asStateFlow()

    override suspend fun refresh(): ProviderUsageSnapshot {
        if (!isEnabled()) clear()
        return state.value
    }

    @HighFrequency
    fun receive(update: JsonObject) {
        val snapshot = kimiProviderUsage(update, Clock.System.now()) ?: return
        log.v { "mutableState: ${mutableState.value} -> $snapshot" }
        mutableState.value = snapshot
    }

    fun clear() {
        log.i { "mutableState: ${mutableState.value} -> ${ProviderUsageSnapshot()}" }
        mutableState.value = ProviderUsageSnapshot()
    }
}

private fun ContextUsage?.toLogString(): String =
    this?.let { usage -> "used=${usage.usedTokens}, capacity=${usage.capacityTokens ?: "unknown"}" } ?: "unknown"

private val log = Log.tag("KimiUsage")

internal fun kimiContextUsage(update: JsonObject, now: Instant): ContextUsage? {
    if (update.text("sessionUpdate") != "usage_update") return null
    val used = update.nonnegativeLong("used") ?: return null
    val size = update.positiveLong("size") ?: return null
    return ContextUsage(used, size, Observation(now, isStale = false))
}

internal fun kimiProviderUsage(update: JsonObject, now: Instant): ProviderUsageSnapshot? {
    val kimi = (update["_meta"] as? JsonObject)?.get("kimiCode") as? JsonObject ?: return null
    val limits = kimi["rateLimits"] as? JsonObject ?: return null
    val summary = limits["summary"] as? JsonObject
    val additional = limits["limits"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
    val windows = buildList {
        summary?.let { add(kimiUsageWindow(it, "summary")) }
        additional.forEachIndexed { index, value -> add(kimiUsageWindow(value, "limit-$index")) }
    }
    return ProviderUsageSnapshot(
        windows = windows,
        credits = kimiUsageCredits(limits["booster"] as? JsonObject),
        planName = limits.text("planName") ?: limits.text("plan"),
        observation = Observation(now, isStale = false),
    )
}

private fun kimiUsageWindow(value: JsonObject, fallbackId: String): ProviderUsageWindow {
    val window = value.text("window")
    return ProviderUsageWindow(
        id = value.text("id") ?: window ?: fallbackId,
        title = value.text("name") ?: value.text("title") ?: window ?: "Kimi",
        usedPercent = value.percent("used", "limit"),
        resetsAt = value.text("resetAt")?.let(Instant::parseOrNull),
        windowDurationMinutes = value.positiveLong("windowDurationMinutes") ?: window?.let(::kimiWindowMinutes),
    )
}

private fun kimiUsageCredits(value: JsonObject?): ProviderUsageCredits? {
    value ?: return null
    val remaining = value.nonnegativeDouble("balanceCents")?.let { it / CENTS_PER_UNIT }
    val isUnlimited = (value["unlimited"] as? JsonPrimitive)?.booleanOrNull == true
    if (remaining == null && !isUnlimited) return null
    return ProviderUsageCredits(
        remaining = remaining,
        total = value.nonnegativeDouble("totalCents")?.let { it / CENTS_PER_UNIT },
        currency = value.text("currency"),
        isUnlimited = isUnlimited,
        expiresAt = value.text("expiresAt")?.let(Instant::parseOrNull),
    )
}

private fun JsonObject.percent(usedKey: String, limitKey: String): Double? {
    val used = nonnegativeDouble(usedKey) ?: return null
    val limit = nonnegativeDouble(limitKey)?.takeIf { it > 0 } ?: return null
    return (used / limit * PERCENT).coerceAtMost(PERCENT)
}

private fun kimiWindowMinutes(value: String): Long? {
    val match = Regex("^(\\d+)([mhdw])$").matchEntire(value.lowercase()) ?: return null
    val amount = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val multiplier = when (match.groupValues[2]) {
        "m" -> 1L
        "h" -> MINUTES_PER_HOUR
        "d" -> MINUTES_PER_DAY
        else -> MINUTES_PER_WEEK
    }
    return if (amount > Long.MAX_VALUE / multiplier) null else amount * multiplier
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.nonnegativeLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

private fun JsonObject.positiveLong(key: String): Long? = nonnegativeLong(key)?.takeIf { it > 0 }

private fun JsonObject.nonnegativeDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

private const val PERCENT = 100.0
private const val CENTS_PER_UNIT = 100.0
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY
