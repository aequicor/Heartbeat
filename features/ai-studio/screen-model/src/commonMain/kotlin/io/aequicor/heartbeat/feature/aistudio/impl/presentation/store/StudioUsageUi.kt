@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableListSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableMapSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableSetSerializer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Instant

/** Confirmed context occupancy of one conversation. */
@Serializable
@SerialName("ContextUsageUi")
@Immutable
data class ContextUsageUi(val usedTokens: Long, val capacityTokens: Long, val percent: Int)

/** One known provider quota window. */
@Serializable
@SerialName("UsageWindowUi")
@Immutable
data class UsageWindowUi(
    val id: String,
    val title: String,
    val percent: Int,
    val resetsAt: Instant?,
    val windowDurationMinutes: Long? = null,
)

/** Provider-reported credits without inferred currency or allowance. */
@Serializable
@SerialName("UsageCreditsUi")
@Immutable
data class UsageCreditsUi(
    val remaining: Double?,
    val total: Double?,
    val currency: String?,
    val isUnlimited: Boolean,
    val expiresAt: Instant?,
)

/** Account telemetry projected for display; empty metrics hide the control. */
@Serializable
@SerialName("ProviderUsageUi")
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
