package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerUseSessionBoundsTest {
    @Test
    fun `session uses right edge of a monitor with negative desktop coordinates`() {
        assertEquals(
            Rectangle(-420, 110, 420, 900),
            computerUseSessionBounds(Rectangle(-1920, 40, 1920, 1040), 420, 900),
        )
    }

    @Test
    fun `small work area constrains session to preserve taskbar and menu bar`() {
        assertEquals(
            Rectangle(20, -800, 360, 640),
            computerUseSessionBounds(Rectangle(20, -800, 360, 640), 420, 900),
        )
    }

    @Test
    fun `ending computer use restores maximized placement floating geometry and minimized state`() {
        val originalSize = DpSize(Dp(1280f), Dp(900f))
        val originalPosition = WindowPosition.Absolute(Dp(-1200f), Dp(80f))
        val state = WindowState(
            placement = WindowPlacement.Maximized,
            isMinimized = true,
            position = originalPosition,
            size = originalSize,
        )
        val snapshot = DesktopWindowSnapshot(state)
        state.placement = WindowPlacement.Floating
        state.isMinimized = false
        state.size = DpSize(Dp(420f), Dp(640f))
        state.position = WindowPosition.Absolute(Dp(-420f), Dp(40f))
        snapshot.restore(state)
        assertEquals(WindowPlacement.Maximized, state.placement)
        assertEquals(true, state.isMinimized)
        assertEquals(originalSize, state.size)
        assertEquals(originalPosition, state.position)
    }
}
