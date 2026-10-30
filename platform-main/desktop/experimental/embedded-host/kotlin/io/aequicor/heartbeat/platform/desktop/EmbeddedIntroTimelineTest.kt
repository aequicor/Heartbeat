// Test fixtures intentionally use authored timestamps and readable behavior names outside src/test.
@file:Suppress("MagicNumber", "FunctionNaming")

package io.aequicor.heartbeat.platform.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedIntroTimelineTest {
    @Test
    fun `slow JVM holds the same quiet native frame indefinitely`() {
        assertEquals(4_200L, EmbeddedIntroTimeline.nativeElapsed(90_000, 100, true))
        assertEquals(4_200L, EmbeddedIntroTimeline.nativeElapsed(100, 100, false))
        assertEquals(0L, EmbeddedIntroTimeline.nativeElapsed(99, 100, true))
    }

    @Test
    fun `first Compose frame matches native until cover removal is acknowledged`() {
        assertEquals(EmbeddedIntroFrame(0.0, 1.0), EmbeddedIntroTimeline.continuation(90_000, null))
        assertEquals(EmbeddedIntroFrame(0.0, 1.0), EmbeddedIntroTimeline.continuation(90_000, 90_000))
    }

    @Test
    fun `Studio is revealed only after the logo transformation`() {
        val start = 10_000L
        val middle = EmbeddedIntroTimeline.continuation(start + 600, start)
        assertTrue(middle.transform in 0.49..0.51)
        assertEquals(1.0, middle.opacity)
        assertEquals(EmbeddedIntroFrame(1.0, 1.0), EmbeddedIntroTimeline.continuation(start + 1_200, start))
        assertEquals(EmbeddedIntroFrame(1.0, 0.0), EmbeddedIntroTimeline.continuation(start + 1_800, start))
    }
}
