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

    @Test
    fun `macOS blends interop into the main canvas while retaining window backed popups`() {
        val expected = mapOf("compose.layers.type" to "WINDOW", "compose.interop.blending" to "true")
        assertEquals(expected, desktopRenderingProperties("Mac OS X", "aarch64"))
        assertEquals(expected, desktopRenderingProperties("macOS", "x86_64"))
    }

    @Test
    fun `other hosts retain their renderer settings without enabling macOS interop blending`() {
        assertEquals(
            mapOf("compose.layers.type" to "WINDOW", "skiko.renderApi" to "OPENGL"),
            desktopRenderingProperties("Windows 11", "amd64"),
        )
        assertEquals(
            mapOf("compose.layers.type" to "WINDOW", "skiko.renderApi" to "SOFTWARE_FAST"),
            desktopRenderingProperties("Windows 11", "arm64"),
        )
        assertEquals(mapOf("compose.layers.type" to "WINDOW"), desktopRenderingProperties("Linux", "amd64"))
    }
}
