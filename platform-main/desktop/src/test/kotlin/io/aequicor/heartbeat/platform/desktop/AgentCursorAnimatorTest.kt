package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseGesture
import io.aequicor.heartbeat.feature.computeruse.api.MouseButton
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentCursorAnimatorTest {
    private val timing = CursorTiming(
        glideMillis = 100,
        rippleMillis = 300,
        trailMillis = 200,
        badgeMillis = 1000,
        isReducedMotion = false,
    )

    @Test
    fun `the first point appears in place and later points ease towards the target`() {
        val animator = AgentCursorAnimator(timing)
        assertNull(animator.scene(0))
        animator.update(CursorPoint(0.0, 0.0), ComputerUseGesture.Move, 0, nowMillis = 0)
        assertEquals(CursorPoint(0.0, 0.0), assertNotNull(animator.scene(0)).tip)
        animator.update(CursorPoint(100.0, 0.0), ComputerUseGesture.Move, 0, nowMillis = 1_000)
        assertEquals(CursorPoint(0.0, 0.0), assertNotNull(animator.scene(1_000)).tip)
        val half = assertNotNull(animator.scene(1_050))
        assertTrue(half.tip.x > 50.0, "ease-out covers most of the distance early")
        assertTrue(half.isAnimating)
        val done = assertNotNull(animator.scene(1_100))
        assertEquals(CursorPoint(100.0, 0.0), done.tip)
        assertFalse(done.isAnimating)
    }

    @Test
    fun `reduced motion jumps and keeps effects static`() {
        val animator = AgentCursorAnimator(timing.copy(isReducedMotion = true))
        animator.update(CursorPoint(0.0, 0.0), ComputerUseGesture.Move, 0, nowMillis = 0)
        animator.update(CursorPoint(80.0, 40.0), ComputerUseGesture.Press(MouseButton.Left, 1), 1, nowMillis = 10)
        val scene = assertNotNull(animator.scene(10))
        assertEquals(CursorPoint(80.0, 40.0), scene.tip)
        assertEquals(listOf(CursorRing(1f, 1f)), scene.rings)
    }

    @Test
    fun `a click plays one ring per click once per effect`() {
        val animator = AgentCursorAnimator(timing)
        val press = ComputerUseGesture.Press(MouseButton.Left, 2)
        animator.update(CursorPoint(10.0, 10.0), press, 1, nowMillis = 0)
        assertEquals(1, assertNotNull(animator.scene(50)).rings.size)
        assertEquals(2, assertNotNull(animator.scene(150)).rings.size)
        animator.update(CursorPoint(10.0, 10.0), press, 1, nowMillis = 1_000)
        assertTrue(assertNotNull(animator.scene(1_000)).rings.isEmpty())
        assertFalse(assertNotNull(animator.scene(1_000)).isAnimating)
    }

    @Test
    fun `a drag keeps its trail while held and fades it afterwards`() {
        val animator = AgentCursorAnimator(timing)
        val drag = ComputerUseGesture.Drag(MouseButton.Left)
        animator.update(CursorPoint(0.0, 0.0), drag, 1, nowMillis = 0)
        animator.update(CursorPoint(20.0, 0.0), drag, 1, nowMillis = 500)
        animator.update(CursorPoint(20.0, 0.0), drag, 1, nowMillis = 600)
        assertEquals(listOf(1f, 1f), assertNotNull(animator.scene(900)).trail.map { it.second })
        animator.update(CursorPoint(20.0, 0.0), ComputerUseGesture.Move, 1, nowMillis = 900)
        assertEquals(listOf(0.5f, 0.5f), assertNotNull(animator.scene(1_000)).trail.map { it.second })
        val faded = assertNotNull(animator.scene(1_100))
        assertTrue(faded.trail.isEmpty())
        assertFalse(faded.isAnimating, "the frame timer stops once the released trail faded")
    }

    @Test
    fun `new timing and hidden effects never replay an effect already seen`() {
        val animator = AgentCursorAnimator(timing)
        val keys = ComputerUseGesture.Keys(listOf("enter"))
        animator.update(CursorPoint(0.0, 0.0), keys, 1, nowMillis = 0)
        animator.timing = timing.copy(isReducedMotion = true)
        animator.update(CursorPoint(0.0, 0.0), keys, 1, nowMillis = 5_000)
        assertNull(assertNotNull(animator.scene(5_000)).badge)
        animator.reset(effectSequence = 2)
        animator.update(CursorPoint(0.0, 0.0), keys, 2, nowMillis = 6_000)
        assertNull(assertNotNull(animator.scene(6_000)).badge)
    }

    @Test
    fun `labels name keys and never carry typed text`() {
        assertEquals("Ctrl+S", ComputerUseGesture.Keys(listOf("ctrl", "s")).label())
        assertEquals("⌨ 6", ComputerUseGesture.Type(6).label())
        assertEquals("↑ 3", ComputerUseGesture.Scroll(-3).label())
        assertEquals("R", ComputerUseGesture.Press(MouseButton.Right, 1).label())
        assertNull(ComputerUseGesture.Press(MouseButton.Left, 1).label())
        val animator = AgentCursorAnimator(timing)
        animator.update(CursorPoint(0.0, 0.0), ComputerUseGesture.Keys(listOf("enter")), 1, nowMillis = 0)
        assertEquals(CursorBadge("Enter", 1f), assertNotNull(animator.scene(100)).badge)
        assertTrue(assertNotNull(assertNotNull(animator.scene(900)).badge).alpha < 1f)
        assertNull(assertNotNull(animator.scene(1_000)).badge)
    }

    @Test
    fun `the canvas stays on the screen that holds the tip`() {
        val screen = Rectangle(0, 0, 1920, 1080)
        assertEquals(Point(412, 212), canvasOrigin(CursorPoint(500.0, 300.0), 176, screen))
        assertEquals(Point(0, 0), canvasOrigin(CursorPoint(10.0, 10.0), 176, screen))
        assertEquals(Point(1744, 904), canvasOrigin(CursorPoint(1919.0, 1079.0), 176, screen))
        assertEquals(Point(-78, -78), canvasOrigin(CursorPoint(10.0, 10.0), 176, null))
    }
}
