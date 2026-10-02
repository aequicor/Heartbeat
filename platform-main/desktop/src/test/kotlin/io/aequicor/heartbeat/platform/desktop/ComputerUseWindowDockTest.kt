package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerUseWindowDockTest {
    @Test
    fun `floating window is docked and gets its bounds back`() = runTest {
        val window = FakeWindow(NORMAL, WindowPlacement.Floating)
        val session = launch { ComputerUseWindowDock(window).holdPinned(WIDTH, HEIGHT) }
        advanceUntilIdle()
        window.settle()
        assertEquals(DOCKED, window.bounds)

        session.cancelAndJoin()
        window.settle()
        assertEquals(WindowPlacement.Floating, window.placement)
        assertEquals(NORMAL, window.bounds)
    }

    @Test
    fun `maximized window is docked after leaving maximized and unmaximizes to its original bounds`() = runTest {
        val window = FakeWindow(NORMAL, WindowPlacement.Maximized)
        val session = launch { ComputerUseWindowDock(window).holdPinned(WIDTH, HEIGHT) }
        advanceUntilIdle()
        window.settle()
        assertEquals(WindowPlacement.Floating, window.placement)
        assertEquals(DOCKED, window.bounds)

        session.cancelAndJoin()
        window.settle()
        assertEquals(WindowPlacement.Maximized, window.placement)
        assertEquals(NORMAL, window.floatingBounds)
    }

    @Test
    fun `fullscreen window is docked once macOS has left fullscreen and returns to it`() = runTest {
        val window = FakeWindow(NORMAL, WindowPlacement.Fullscreen, fullscreenTicks = 40)
        val session = launch { ComputerUseWindowDock(window).holdPinned(WIDTH, HEIGHT) }
        advanceUntilIdle()
        window.settle()
        assertEquals(WindowPlacement.Floating, window.placement)
        assertEquals(DOCKED, window.bounds)

        session.cancelAndJoin()
        window.settle()
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        assertEquals(NORMAL, window.floatingBounds)
    }

    @Test
    fun `minimized window is shown for the session and minimized again with its bounds`() = runTest {
        val window = FakeWindow(NORMAL, WindowPlacement.Floating, isMinimized = true)
        val session = launch { ComputerUseWindowDock(window).holdPinned(WIDTH, HEIGHT) }
        advanceUntilIdle()
        window.settle()
        assertFalse(window.isMinimized)
        assertEquals(DOCKED, window.bounds)

        session.cancelAndJoin()
        window.settle()
        assertTrue(window.isMinimized)
        assertEquals(WindowPlacement.Floating, window.placement)
        assertEquals(NORMAL, window.floatingBounds)
    }

    @Test
    fun `session ending while the window still leaves fullscreen returns it to fullscreen`() = runTest {
        val window = FakeWindow(NORMAL, WindowPlacement.Fullscreen, fullscreenTicks = 40)
        val session = launch { ComputerUseWindowDock(window).holdPinned(WIDTH, HEIGHT) }
        advanceTimeBy(TICK_MS * 5)
        runCurrent()
        assertEquals(WindowPlacement.Fullscreen, window.placement)

        session.cancelAndJoin()
        window.settle()
        assertEquals(WindowPlacement.Fullscreen, window.placement)
        assertEquals(NORMAL, window.floatingBounds)
    }

    @Test
    fun `next session waits until the previous one has restored the window`() = runTest {
        val window = FakeWindow(NORMAL, WindowPlacement.Maximized)
        val dock = ComputerUseWindowDock(window)
        val first = launch { dock.holdPinned(WIDTH, HEIGHT) }
        advanceUntilIdle()
        window.settle()
        first.cancel()
        val second = launch { dock.holdPinned(WIDTH, HEIGHT) }
        advanceUntilIdle()
        window.settle()
        assertEquals(DOCKED, window.bounds)

        second.cancelAndJoin()
        window.settle()
        assertEquals(WindowPlacement.Maximized, window.placement)
        assertEquals(NORMAL, window.floatingBounds)
    }

    @Test
    fun `minimized window stays minimized when a new session starts during its restore`() = runTest {
        val window = FakeWindow(NORMAL, WindowPlacement.Floating, isMinimized = true)
        val dock = ComputerUseWindowDock(window)
        val first = launch { dock.holdPinned(WIDTH, HEIGHT) }
        advanceUntilIdle()
        first.cancel()
        val second = launch { dock.holdPinned(WIDTH, HEIGHT) }
        advanceUntilIdle()
        window.settle()
        assertEquals(DOCKED, window.bounds)
        second.cancelAndJoin()
        window.settle()
        assertTrue(window.isMinimized)
        assertEquals(NORMAL, window.floatingBounds)
    }

    private companion object {
        const val WIDTH = 420
        const val HEIGHT = 900
        const val TICK_MS = 16L
        val SCREEN = Rectangle(0, 0, 1920, 1080)
        val WORK_AREA = Rectangle(0, 25, 1920, 1055)
        val NORMAL = Rectangle(160, 120, 1280, 820)
        val DOCKED = computerUseSessionBounds(WORK_AREA, WIDTH, HEIGHT)
    }

    /**
     * Mirrors Compose's window state sync — size and position are applied before placement and only while the
     * requested placement is floating; resize, move and state events copy native facts back into the state after
     * the update — and a window manager that ignores floating geometry of a maximized/fullscreen window, restores
     * its own floating bounds when the window leaves those states and enters or leaves fullscreen asynchronously.
     */
    private class FakeWindow(
        floating: Rectangle,
        placement: WindowPlacement,
        isMinimized: Boolean = false,
        private val fullscreenTicks: Int = 0,
    ) : DockableWindow {
        /** Bounds the window manager gives the window when it is floating. */
        var floatingBounds = Rectangle(floating)
            private set
        private var nativePlacement = placement
        private var nativeMinimized = isMinimized
        private var transition: WindowPlacement? = null
        private var transitionTicks = 0
        private var isResized = false
        private var isMoved = false
        private var isStateChanged = false

        override val state = WindowState(placement, isMinimized, position(bounds), size(bounds))
        private var appliedSize = state.size
        private var appliedPosition = state.position
        private var appliedPlacement = state.placement
        private var appliedMinimized = state.isMinimized

        override val isAlive = true
        override val placement get() = nativePlacement
        override val isMinimized get() = nativeMinimized
        override val bounds: Rectangle
            get() = when (nativePlacement) {
                WindowPlacement.Floating -> Rectangle(floatingBounds)
                WindowPlacement.Maximized -> Rectangle(WORK_AREA)
                WindowPlacement.Fullscreen -> Rectangle(SCREEN)
            }
        override val workArea get() = Rectangle(WORK_AREA)

        override suspend fun awaitToolkit() {
            delay(TICK_MS)
            tick()
        }

        /** The toolkit keeps applying requests after the dock stops waiting for them. */
        fun settle() = repeat(fullscreenTicks * 2 + 10) { tick() }

        private fun tick() {
            advanceTransition()
            applyRequests()
            deliverEvents()
        }

        private fun applyRequests() {
            if (state.size != appliedSize) {
                if (state.placement == WindowPlacement.Floating) resize(state.size)
                appliedSize = state.size
            }
            if (state.position != appliedPosition) {
                if (state.placement == WindowPlacement.Floating) move(state.position as WindowPosition.Absolute)
                appliedPosition = state.position
            }
            if (state.placement != appliedPlacement) {
                place(state.placement)
                appliedPlacement = state.placement
            }
            if (state.isMinimized != appliedMinimized) {
                nativeMinimized = state.isMinimized
                appliedMinimized = state.isMinimized
                isStateChanged = true
            }
        }

        private fun isFloatingNow() = nativePlacement == WindowPlacement.Floating && transition == null

        private fun resize(size: DpSize) {
            if (!isFloatingNow()) return
            val current = floatingBounds
            floatingBounds = Rectangle(current.x, current.y, size.width.value.toInt(), size.height.value.toInt())
            isResized = true
        }

        private fun move(position: WindowPosition.Absolute) {
            if (!isFloatingNow()) return
            floatingBounds = Rectangle(floatingBounds).apply {
                setLocation(position.x.value.toInt(), position.y.value.toInt())
            }
            isMoved = true
        }

        private fun place(target: WindowPlacement) {
            if (target == nativePlacement && transition == null) return
            if (nativePlacement == WindowPlacement.Fullscreen || target == WindowPlacement.Fullscreen) {
                transition = target
                transitionTicks = fullscreenTicks
            } else {
                nativePlacement = target
                isResized = true
                isMoved = true
                isStateChanged = true
            }
        }

        // Fullscreen changes fire only resize and move events, which is why Compose reads placement on resize.
        private fun advanceTransition() {
            val target = transition ?: return
            if (transitionTicks-- > 0) return
            nativePlacement = target
            transition = null
            isResized = true
            isMoved = true
        }

        /** Compose's window listeners copy the native window into the state and mark it applied. */
        private fun deliverEvents() {
            val current = bounds
            if (isResized || isStateChanged) {
                state.placement = nativePlacement
                appliedPlacement = nativePlacement
            }
            if (isResized) {
                state.size = size(current)
                appliedSize = state.size
            }
            if (isMoved) {
                state.position = position(current)
                appliedPosition = state.position
            }
            if (isStateChanged) {
                state.isMinimized = nativeMinimized
                appliedMinimized = nativeMinimized
            }
            isResized = false
            isMoved = false
            isStateChanged = false
        }

        private fun size(bounds: Rectangle) = DpSize(Dp(bounds.width.toFloat()), Dp(bounds.height.toFloat()))

        private fun position(bounds: Rectangle) =
            WindowPosition.Absolute(Dp(bounds.x.toFloat()), Dp(bounds.y.toFloat()))
    }
}
