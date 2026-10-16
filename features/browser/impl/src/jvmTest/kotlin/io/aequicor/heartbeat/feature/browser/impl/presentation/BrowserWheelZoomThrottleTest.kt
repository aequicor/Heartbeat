package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserWheelZoomThrottleTest {
    @Test
    fun `listener steps delayed by IPC jitter are still accepted`() {
        var now = 0L
        val throttle = BrowserWheelZoomThrottle { now }
        // Steps sent 80 ms apart by the listener; the first is delivered 30 ms late, the second on time.
        val accepted = listOf(30L, 80L, 160L).map { millis ->
            now = millis * 1_000_000
            throttle.tryAcquire()
        }
        assertEquals(listOf(true, true, true), accepted)
    }

    @Test
    fun `delivery jitter beyond the margin drops the later step`() {
        var now = 31_000_000L
        val throttle = BrowserWheelZoomThrottle { now }
        assertEquals(true, throttle.tryAcquire())
        now = 80_000_000L
        assertEquals(false, throttle.tryAcquire())
    }

    @Test
    fun `direct bridge calls are limited to one step per interval`() {
        var now = 0L
        val throttle = BrowserWheelZoomThrottle { now }
        val accepted = listOf(0L, 10L, 49L, 50L, 51L, 99L, 100L, 1_000L).map { millis ->
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
