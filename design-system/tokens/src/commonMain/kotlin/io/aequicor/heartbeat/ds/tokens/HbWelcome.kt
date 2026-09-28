package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Timing and geometry of the welcome screen. Text styles come from [HbTypography]; the screen sits in the same
 * flat window as the studio, so it has no decorative backdrop or oversized type of its own.
 * [symbolStart], [titleStart] and [actionsStart] are fractions of [durationMillis] at which each part fades in.
 */
@Immutable
data class HbWelcome(
    val durationMillis: Int = 6000,
    val symbolStart: Float = 0f,
    val titleStart: Float = 1.5f / 6f,
    val actionsStart: Float = 4f / 6f,
    val maxWidth: Dp = 440.dp,
    val symbolSize: Dp = 64.dp,
    val sectionGap: Dp = 24.dp,
    /** Upward drift of each part while it fades in. */
    val revealOffset: Dp = 8.dp,
)
