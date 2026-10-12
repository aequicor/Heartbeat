package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage

/** Points below the threshold the context must drop to before automatic attempts start over. */
public const val COMPACTION_HYSTERESIS_PERCENT: Int = 10

/** Automatic compaction after a settled turn: a pending request (or none) and the next mark of the session. */
public data class CompactionDecision(val pending: PendingCompaction?, val mark: CompactionMark)

/** Occupancy of [this] usage in whole percent. */
public val ContextUsage.percent: Int get() = (usedTokens * PERCENT / capacityTokens).toInt()

/**
 * Decides automatic compaction for a settled turn. Unknown [usage] changes nothing. Below the threshold minus
 * [COMPACTION_HYSTERESIS_PERCENT] the attempts start over. At or above the threshold each settled turn tries the
 * next supported method of the strategy (Auto: native, then rollover), one per turn; when none is left the session
 * is exhausted, so a context that compaction cannot shrink never loops.
 */
public fun ContextOptimizationSettings.decideCompaction(
    mark: CompactionMark,
    usage: ContextUsage?,
    support: CompactionSupport,
): CompactionDecision {
    val occupancy = usage?.percent ?: return CompactionDecision(null, mark)
    return when {
        occupancy < thresholdPercent - COMPACTION_HYSTERESIS_PERCENT -> CompactionDecision(null, CompactionMark())

        occupancy < thresholdPercent || !isAutoCompactionEnabled || mark.isExhausted -> CompactionDecision(null, mark)

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
