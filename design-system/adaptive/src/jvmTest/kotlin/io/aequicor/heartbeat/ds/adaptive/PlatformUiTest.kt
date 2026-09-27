package io.aequicor.heartbeat.ds.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformUiTest {
    @Test
    fun `desktop kit follows operating system independent of case`() {
        assertEquals(PlatformUi.Fluent, desktopPlatformUi("Windows 11"))
        assertEquals(PlatformUi.Fluent, desktopPlatformUi("WINDOWS 10"))
        assertEquals(PlatformUi.MacOs, desktopPlatformUi("Mac OS X"))
        assertEquals(PlatformUi.MacOs, desktopPlatformUi("macOS"))
        assertEquals(PlatformUi.Material, desktopPlatformUi("Linux"))
        assertEquals(PlatformUi.Material, desktopPlatformUi(""))
        assertEquals(PlatformUi.Material, desktopPlatformUi("Darwin"))
    }
}
