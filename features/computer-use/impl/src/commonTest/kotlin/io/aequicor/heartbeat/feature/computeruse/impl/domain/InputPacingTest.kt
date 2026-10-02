package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputPacingTest {
    @Test
    fun `wheel notches round to the nearest notch`() {
        assertEquals(3, wheelNotches(120))
        assertEquals(-3, wheelNotches(-100))
        assertEquals(1, wheelNotches(30))
        assertEquals(0, wheelNotches(0))
        assertEquals(0, wheelNotches(10))
    }

    @Test
    fun `wheel notches are clamped to the maximum`() {
        assertEquals(MAX_WHEEL_NOTCHES, wheelNotches(100_000))
        assertEquals(-MAX_WHEEL_NOTCHES, wheelNotches(-100_000))
    }

    @Test
    fun `extreme deltas keep their direction and the clamp`() {
        assertEquals(MAX_WHEEL_NOTCHES, wheelNotches(Int.MAX_VALUE))
        assertEquals(-MAX_WHEEL_NOTCHES, wheelNotches(Int.MIN_VALUE))
        val extreme = inputWaitLimitMillis(InputAction.Scroll(FramePoint(0.0, 0.0), deltaY = Int.MIN_VALUE))
        assertEquals(inputWaitLimitMillis(InputAction.Scroll(FramePoint(0.0, 0.0), deltaY = -100_000)), extreme)
    }

    @Test
    fun `every action has a wait limit of at least the base wait`() {
        val actions = listOf(
            InputAction.MoveTo(FramePoint(0.0, 0.0)),
            InputAction.Click(FramePoint(0.0, 0.0), count = 3),
            InputAction.Drag(FramePoint(0.0, 0.0), FramePoint(10.0, 10.0)),
            InputAction.Scroll(FramePoint(0.0, 0.0), deltaY = 120),
            InputAction.Type("text"),
            InputAction.Key(listOf("ctrl", "s")),
        )
        for (action in actions) {
            assertTrue(inputWaitLimitMillis(action) >= BASE_WAIT_MILLIS, "action=$action")
        }
    }

    @Test
    fun `longer text gets a proportionally longer wait limit`() {
        val short = inputWaitLimitMillis(InputAction.Type("a"))
        val long = inputWaitLimitMillis(InputAction.Type("a".repeat(1_000)))
        assertTrue(long > short)
        assertTrue(long >= 30_000L + 2 * 1_000 * 60)
    }
}
