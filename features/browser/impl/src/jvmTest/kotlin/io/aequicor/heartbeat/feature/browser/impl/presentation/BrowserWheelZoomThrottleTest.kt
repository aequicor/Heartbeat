package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserWheelZoomThrottleTest {
    @Test
    fun `direct bridge calls are limited to one step per interval`() {
        var now = 0L
        val throttle = BrowserWheelZoomThrottle { now }
        val accepted = listOf(0L, 10L, 79L, 80L, 81L, 159L, 160L, 1_000L).map { millis ->
            now = millis * 1_000_000
            throttle.tryAcquire()
        }
        assertEquals(listOf(true, false, false, true, false, false, true, true), accepted)
    }

    @Test
    fun `the first call is accepted at any clock origin`() {
        val throttle = BrowserWheelZoomThrottle { Long.MIN_VALUE + 1 }
        assertEquals(true, throttle.tryAcquire())
        assertEquals(false, throttle.tryAcquire())
    }
}
