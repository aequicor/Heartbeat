package io.aequicor.heartbeat.feature.aistudio.screenui

import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageCreditsUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageWindowUi
import kotlin.math.roundToInt

/** Highest reported consumption wins; a nearer known reset breaks ties without depending on input order. */
internal fun ProviderUsageUi.indicatorWindow(): UsageWindowUi? = windows.sortedWith(
    compareByDescending<UsageWindowUi> { it.percent }
        .thenBy { it.resetsAt == null }
        .thenBy { it.resetsAt }
        .thenBy { it.id },
).firstOrNull()

/** Explicit durations win; a provider's named period still identifies its chip when duration is absent. */
internal fun UsageWindowUi.chipPeriodMinutes(): Long? {
    val period = title.lowercase()
    return windowDurationMinutes ?: when {
        period == "five_hour" -> FIVE_HOURS
        period == "weekly" || period == "week" || period.startsWith("seven_day") -> MINUTES_PER_WEEK
        period == "monthly" || period == "month" -> MINUTES_PER_MONTH
        else -> null
    }
}

/** Credit details report consumption separately from the remaining balance; unknown allowances stay unknown. */
internal fun UsageCreditsUi.consumedPercent(): Int? {
    val allowance = total?.takeIf { it.isFinite() && it > 0 } ?: return null
    val balance = remaining?.takeIf { it.isFinite() && it >= 0 } ?: return null
    if (isUnlimited) return null
    return ((1 - balance / allowance) * PERCENT_MAX).coerceIn(0.0, PERCENT_MAX).roundToInt()
}

private const val PERCENT_MAX = 100.0
internal const val MINUTES_PER_HOUR = 60L
internal const val MINUTES_PER_WEEK = 10080L
internal const val MINUTES_PER_MONTH = 43200L
private const val FIVE_HOURS = 300L
