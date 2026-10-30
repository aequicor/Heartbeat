package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsHandoffCoverTest {
    @Test
    fun preparingFrameIsInvisibleAndExcludedFromSwitcherThenRestoresItsStyle() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        EventQueue.invokeAndWait {
            val window = JFrame()
            try {
                window.addNotify()
                val handle = HWND(Native.getWindowPointer(window))
                val user = User32.INSTANCE
                val original = user.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
                val cover = assertNotNull(WindowsHandoffCover.create(window))
                try {
                    assertFalse(window.isAutoRequestFocus)
                    val hidden = user.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
                    assertTrue(hidden and TOOLWINDOW != 0)
                    assertEquals(0, hidden and APPWINDOW)
                    assertEquals(0, alpha(handle))
                    assertFalse(cover.reveal())
                    assertEquals(255, alpha(handle))
                    assertTrue(cover.reveal())
                    cover.hidePixels()
                    assertEquals(0, alpha(handle))
                    assertFalse(cover.reveal())
                    assertTrue(cover.reveal())
                } finally {
                    cover.close()
                }
                val restored = user.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
                assertEquals((original and TOOLWINDOW.inv()) or APPWINDOW or WinUser.WS_EX_LAYERED, restored)
                assertEquals(0, restored and TOOLWINDOW)
                assertTrue(restored and APPWINDOW != 0)
                assertEquals(255, alpha(handle))
                assertTrue(window.isAutoRequestFocus)
            } finally {
                window.dispose()
            }
        }
    }

    private fun alpha(handle: HWND): Int {
        val alpha = ByteByReference()
        val function = NativeLibrary.getInstance(
            "user32",
        ).getFunction("GetLayeredWindowAttributes", Function.ALT_CONVENTION)
        check(function.invokeInt(arrayOf(handle, IntByReference(), alpha, IntByReference())) != 0)
        return alpha.value.toInt() and 0xff
    }

    companion object {
        private const val TOOLWINDOW = 0x80
        private const val APPWINDOW = 0x40000
    }
}
