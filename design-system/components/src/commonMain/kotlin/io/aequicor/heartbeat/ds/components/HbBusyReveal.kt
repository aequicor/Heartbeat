package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.delay

/**
 * Whether [isBusy] has lasted longer than [io.aequicor.heartbeat.ds.tokens.HbMotion.busyRevealDelayMillis].
 * Screens show their "saving" status and dim controls by this value while disabling input and accessibility
 * actions immediately from the raw flag. This value must never decide whether input is accepted: a write that
 * finishes within the delay (a settings switch) changes nothing on screen, so the layout neither jumps nor
 * flashes, while a slow operation still reveals its progress. Clearing is immediate.
 */
@Composable
public fun rememberHbBusyReveal(isBusy: Boolean): Boolean {
    val delayMillis = HbTheme.motion.busyRevealDelayMillis
    var isRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(isBusy, delayMillis) {
        isRevealed = false
        if (isBusy) {
            delay(delayMillis.toLong())
            isRevealed = true
        }
    }
    return isBusy && isRevealed
}
