package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.roundToInt
import kotlin.time.Instant

/** Confirmed context occupancy of one conversation. */
@Immutable
data class ContextUsageUi(val usedTokens: Long, val capacityTokens: Long, val percent: Int)

/** One known provider quota window. */
@Immutable
data class UsageWindowUi(
    val id: String,
    val title: String,
    val percent: Int,
    val resetsAt: Instant?,
    val windowDurationMinutes: Long? = null,
)

/** Provider-reported credits without inferred currency or allowance. */
@Immutable
data class UsageCreditsUi(
    val remaining: Double?,
    val total: Double?,
    val currency: String?,
    val isUnlimited: Boolean,
    val expiresAt: Instant?,
)

/** Account telemetry projected for display; empty metrics hide the control. */
@Immutable
data class ProviderUsageUi(
    val windows: ImmutableList<UsageWindowUi>,
    val credits: UsageCreditsUi?,
    val planName: String?,
    val checkedAt: Instant?,
    val isStale: Boolean,
) {
    val isVisible: Boolean get() = windows.isNotEmpty() || credits != null
}

internal fun ContextUsage.toUi(): ContextUsageUi = ContextUsageUi(
    usedTokens,
    capacityTokens,
    (usedTokens.toDouble() / capacityTokens * PERCENT_MAX).coerceIn(0.0, PERCENT_MAX).roundToInt(),
)

internal fun ProviderUsageSnapshot.toUi(): ProviderUsageUi = ProviderUsageUi(
    windows.mapNotNull { window ->
        val percent = window.usedPercent?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        UsageWindowUi(
            window.id,
            window.title,
            percent.coerceAtMost(PERCENT_MAX).roundToInt(),
            window.resetsAt,
            window.windowDurationMinutes,
        )
    }.toImmutableList(),
    credits?.takeIf { it.isUnlimited || it.remaining?.let { value -> value.isFinite() && value >= 0 } == true }?.let {
        UsageCreditsUi(
            it.remaining,
            it.total?.takeIf { total -> total.isFinite() && total >= 0 },
            it.currency,
            it.isUnlimited,
            it.expiresAt,
        )
    },
    planName,
    observation.checkedAt,
    observation.isStale,
)

private const val PERCENT_MAX = 100.0
