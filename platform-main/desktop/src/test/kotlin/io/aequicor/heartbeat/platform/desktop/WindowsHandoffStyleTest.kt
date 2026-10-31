package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.platform.win32.WinUser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Handoff styles are transient. A revealed Studio window keeps a taskbar entry but must never keep the layered
 * presentation, which makes DWM alpha-compose every Studio frame for the whole session.
 */
class WindowsHandoffStyleTest {
    @Test
    fun revealedWindowKeepsItsTaskbarEntryWithoutTheLayer() {
        val original = WS_EX_TOOLWINDOW or WS_EX_APPWINDOW or WinUser.WS_EX_LAYERED

        assertEquals(WS_EX_APPWINDOW, restoredHandoffStyle(WS_EX_APPWINDOW or WinUser.WS_EX_LAYERED, false))
        assertEquals(0, restoredHandoffStyle(0, false))
        assertEquals(WS_EX_APPWINDOW, restoredHandoffStyle(0, true))
        assertEquals(WS_EX_APPWINDOW or WS_EX_CONTROLPARENT, restoredHandoffStyle(WS_EX_CONTROLPARENT, true))
        assertEquals(0, restoredHandoffStyle(original, true) and WinUser.WS_EX_LAYERED)
        assertEquals(0, restoredHandoffStyle(original, false) and WinUser.WS_EX_LAYERED)
    }

    private companion object {
        private const val WS_EX_TOOLWINDOW = 0x00000080
        private const val WS_EX_APPWINDOW = 0x00040000
        private const val WS_EX_CONTROLPARENT = 0x00010000
    }
}
