package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Automatic safety reviews of the profile while any occupies or waits for the reviewer: [running] of [capacity]
 * slots review now, [queued] wait for a slot, [finishing] were stopped and wait for their session to terminate.
 * [abandoned] never confirmed termination; their slots were released.
 */
@Serializable
@SerialName("SafetyReviewsUi")
@Immutable
data class SafetyReviewsUi(
    val capacity: Int,
    val running: Int = 0,
    val queued: Int = 0,
    val finishing: Int = 0,
    val abandoned: Int = 0,
) {
    /** Slots taken, including stopped reviews still holding theirs. */
    val busy: Int get() = running + finishing
}
