package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
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
