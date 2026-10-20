package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage

/** Points below the threshold the context must drop to before automatic attempts start over. */
public const val COMPACTION_HYSTERESIS_PERCENT: Int = 10

/** Automatic compaction after a settled turn: a pending request (or none) and the next mark of the session. */
public data class CompactionDecision(val pending: PendingCompaction?, val mark: CompactionMark)

/** Occupancy in whole percent, or null without a confirmed capacity; large native counters never overflow. */
public val ContextUsage.percent: Int?
    get() = capacityTokens?.let { capacity ->
        val whole = usedTokens / capacity
        if (whole > Int.MAX_VALUE / PERCENT) return Int.MAX_VALUE
        val remainder = usedTokens % capacity
        var low = 0
        var high = PERCENT - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            // ceil(capacity * mid / 100), split before multiplication to avoid overflow.
            val threshold = (capacity / PERCENT) * mid + ((capacity % PERCENT) * mid + PERCENT - 1) / PERCENT
            if (remainder >= threshold) low = mid else high = mid - 1
        }
        (whole * PERCENT + low).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

/**
 * Decides automatic compaction for a settled turn. Unknown [usage] or capacity changes nothing. Below the
 * threshold minus [COMPACTION_HYSTERESIS_PERCENT] the attempts start over. Below the threshold a session that is
 * not exhausted also starts over: the last compaction worked, so the next crossing tries the first method again.
 * An exhausted session stays exhausted until the context drops below the threshold minus the hysteresis. At or
 * above the threshold each settled turn tries the next supported method of the strategy (Auto: native, then
 * rollover), one per turn; when none is left the session is exhausted, so a context that compaction cannot
 * shrink never loops.
 */
public fun ContextOptimizationSettings.decideCompaction(
    mark: CompactionMark,
    usage: ContextUsage?,
    support: CompactionSupport,
): CompactionDecision {
    val occupancy = usage?.percent ?: return CompactionDecision(null, mark)
    return when {
        occupancy < thresholdPercent - COMPACTION_HYSTERESIS_PERCENT -> CompactionDecision(null, CompactionMark())

        occupancy < thresholdPercent ->
            CompactionDecision(null, if (mark.isExhausted) mark else CompactionMark())

        !isAutoCompactionEnabled || mark.isExhausted -> CompactionDecision(null, mark)

        else -> {
            val method = methods(support).getOrNull(mark.attempts)
            if (method == null) {
                CompactionDecision(null, mark.copy(isExhausted = true))
            } else {
                CompactionDecision(
                    PendingCompaction(method, CompactionOrigin.Auto),
                    mark.copy(attempts = mark.attempts + 1),
                )
            }
        }
    }
}

/** The method for a request without an explicit one: rollover only under the rollover strategy. */
public fun ContextOptimizationSettings.defaultMethod(): CompactionMethod =
    if (strategy == CompactionStrategy.Rollover) CompactionMethod.Rollover else CompactionMethod.Native

private fun ContextOptimizationSettings.methods(support: CompactionSupport): List<CompactionMethod> = when (strategy) {
    CompactionStrategy.Native -> listOf(CompactionMethod.Native)
    CompactionStrategy.Rollover -> listOf(CompactionMethod.Rollover)
    CompactionStrategy.Auto -> listOf(CompactionMethod.Native, CompactionMethod.Rollover)
}.filter { method ->
    when (method) {
        CompactionMethod.Native -> support.isNative
        CompactionMethod.Rollover -> support.isRollover
    }
}

private const val PERCENT = 100
