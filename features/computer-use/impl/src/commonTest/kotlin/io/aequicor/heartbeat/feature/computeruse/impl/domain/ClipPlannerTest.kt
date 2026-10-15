package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipPlannerTest {
    private val none = MotionChange(null, 0.0)

    private fun change(x: Int, fraction: Double) = MotionChange(CaptureRegion(x, 0, 10, 10), fraction)

    @Test
    fun `the baseline is kept and unchanged frames are dropped`() {
        val planner = ClipPlanner<String>(maxFrames = 4, quietMillis = 500, idleMillis = 1_500)
        assertNull(planner.offer("a", 0, none, none))
        assertEquals("b", planner.offer("b", 250, none, none))
        assertEquals(listOf("a"), planner.keyframes.map { it.frame })
        assertFalse(planner.hasChanged)
        assertNull(planner.changedArea)
    }

    @Test
    fun `the smallest interior change gives way while baseline and latest stay`() {
        val planner = ClipPlanner<String>(maxFrames = 3, quietMillis = 500, idleMillis = 1_500)
        planner.offer("a", 0, none, none)
        planner.offer("b", 100, change(0, 0.5), change(0, 0.5))
        planner.offer("c", 200, change(20, 0.1), change(20, 0.1))
        assertEquals("c", planner.offer("d", 300, change(40, 0.3), change(40, 0.3)))
        assertEquals(listOf("a", "b", "d"), planner.keyframes.map { it.frame })
        assertEquals(listOf(0L, 100L, 300L), planner.keyframes.map { it.offsetMillis })
        assertEquals(CaptureRegion(0, 0, 50, 10), planner.changedArea)
    }

    @Test
    fun `the screen settles after quiet time following a change or after idle time without one`() {
        val idle = ClipPlanner<String>(maxFrames = 4, quietMillis = 500, idleMillis = 1_500)
        idle.offer("a", 0, none, none)
        assertFalse(idle.isSettled(1_000))
        assertTrue(idle.isSettled(1_500))
        val busy = ClipPlanner<String>(maxFrames = 4, quietMillis = 500, idleMillis = 1_500)
        busy.offer("a", 0, none, none)
        busy.offer("b", 800, change(0, 0.2), change(0, 0.2))
        assertFalse(busy.isSettled(1_200))
        assertTrue(busy.isSettled(1_300))
        assertTrue(busy.hasChanged)
    }

    @Test
    fun `a slow drift below the threshold per frame still counts once it adds up`() {
        val planner = ClipPlanner<String>(maxFrames = 4, quietMillis = 500, idleMillis = 1_500)
        planner.offer("a", 0, none, none)
        planner.offer("b", 400, none, none)
        assertNull(planner.offer("c", 800, none, change(30, 0.1)))
        assertEquals(listOf("a", "c"), planner.keyframes.map { it.frame })
        assertEquals(CaptureRegion(30, 0, 10, 10), planner.changedArea)
        assertTrue(planner.hasChanged)
        assertFalse(planner.isSettled(1_200))
        assertTrue(planner.isSettled(1_300))
    }
}
