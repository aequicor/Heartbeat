package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbBusyRevealUiTest {
    @Test
    fun `short busy periods never reveal while long ones do and clearing is immediate`() = runSkikoComposeUiTest {
        mainClock.autoAdvance = false
        val delayMillis = HbMotion().busyRevealDelayMillis.toLong()
        var isBusy by mutableStateOf(false)
        var isShown = false
        setContent { HbTheme { isShown = rememberHbBusyReveal(isBusy) } }
        isBusy = true
        mainClock.advanceTimeBy(delayMillis / 2)
        isBusy = false
        mainClock.advanceTimeBy(delayMillis)
        assertFalse(isShown, "A quick write leaves the screen untouched")
        isBusy = true
        mainClock.advanceTimeBy(delayMillis * 2)
        assertTrue(isShown, "A slow operation reveals its progress")
        isBusy = false
        mainClock.advanceTimeByFrame()
        assertFalse(isShown, "Completion hides the progress at once")
    }
}
