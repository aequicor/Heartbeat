package io.aequicor.heartbeat.platform.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopRenderingTest {
    @Test
    fun `Windows x64 avoids synchronous Direct3D redraw`() {
        assertEquals("OPENGL", desktopRenderer("Windows 11", "amd64"))
    }

    @Test
    fun `Windows ARM selects supported software backend for both architecture names`() {
        assertEquals("SOFTWARE_FAST", desktopRenderer("Windows 11", "aarch64"))
        assertEquals("SOFTWARE_FAST", desktopRenderer("Windows 11", "arm64"))
    }

    @Test
    fun `macOS retains its default renderer on both architectures`() {
        assertNull(desktopRenderer("Mac OS X", "aarch64"))
        assertNull(desktopRenderer("Mac OS X", "x86_64"))
    }
}
