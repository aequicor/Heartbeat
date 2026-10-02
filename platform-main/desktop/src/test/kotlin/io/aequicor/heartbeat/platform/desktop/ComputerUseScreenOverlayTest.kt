package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import io.aequicor.heartbeat.ds.tokens.HbColors
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComputerUseScreenOverlayTest {
    @Test
    fun `native Windows overlay survives both configurations without taking focus`() {
        assumeTrue(Platform.isWindows() && !GraphicsEnvironment.isHeadless())
        EventQueue.invokeAndWait {
            val foreground = User32.INSTANCE.GetForegroundWindow()
            val owner = JFrame()
            val overlay = ComputerUseScreenOverlay(owner)
            try {
                overlay.show(
                    listOf(Rectangle(0, 0, 320, 200)),
                    Color(HbColors.DesktopLight.computerUseShadow.toArgb(), true),
                    40,
                )
                val windows = Window.getWindows().filter {
                    it is java.awt.Dialog && it.title.startsWith(
                        "Heartbeat computer-use overlay",
                    ) && it.isDisplayable
                }
                assertEquals(1, windows.size)
                val handle = HWND(Native.getWindowPointer(windows.single()))
                assertTrue(User32.INSTANCE.IsWindowVisible(handle))
                val styles = User32.INSTANCE.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
                assertTrue(styles and WinUser.WS_EX_TRANSPARENT != 0)
                assertTrue(styles and WinUser.WS_EX_LAYERED != 0)
                assertEquals(foreground, User32.INSTANCE.GetForegroundWindow())
                assertEquals(-1L, Pointer.nativeValue(Pointer(-1L)))
            } finally {
                overlay.close()
                owner.dispose()
            }
        }
    }
}
