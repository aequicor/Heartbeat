package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageCreditsUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageWindowUi
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.roundToInt

internal fun ContextUsage.toUi(): ContextUsageUi = ContextUsageUi(
    usedTokens,
    capacityTokens,
    capacityTokens?.let { (usedTokens.toDouble() / it * PERCENT_MAX).coerceIn(0.0, PERCENT_MAX).roundToInt() },
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
