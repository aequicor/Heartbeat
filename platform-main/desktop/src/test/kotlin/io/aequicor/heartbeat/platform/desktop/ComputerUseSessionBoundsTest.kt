package io.aequicor.heartbeat.platform.desktop

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
}
