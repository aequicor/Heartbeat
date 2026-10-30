package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.window.WindowState
import java.awt.AWTException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowVisibilityTest {
    @Test
    fun preparingForHandoffStartsRenderingWithoutRequestingFocus() {
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = true) {}
        visibility.showForHandoff()
        assertTrue(visibility.isVisible)
        assertEquals(0, visibility.restoreRequest)
    }

    @Test
    fun `closing and reopening retain the application and tray until application disposal`() {
        val events = mutableListOf<String>()
        val state = WindowState()
        val visibility = DesktopWindowVisibility(state) { events += "quit" }
        var open: () -> Unit = {}
        visibility.installTray { onOpen, _ ->
            open = onOpen
            AutoCloseable { events += "remove tray" }
        }

        visibility.closeWindow()
        assertFalse(visibility.isVisible)
        assertTrue(events.isEmpty())

        state.isMinimized = true
        open()
        assertTrue(visibility.isVisible)
        assertFalse(state.isMinimized)
        assertEquals(1, visibility.restoreRequest)
        assertTrue(events.isEmpty())

        visibility.closeWindow()
        visibility.close()
        visibility.close()
        assertEquals(listOf("remove tray"), events)
    }

    @Test
    fun `tray quit shuts down the application while its window is hidden`() {
        var quitCount = 0
        val visibility = DesktopWindowVisibility(WindowState()) { quitCount++ }
        var quit: () -> Unit = {}
        visibility.installTray { _, onQuit ->
            quit = onQuit
            AutoCloseable { }
        }

        visibility.closeWindow()
        quit()
        assertEquals(1, quitCount)
        assertFalse(visibility.isVisible)
    }

    @Test
    fun `unsupported or failed tray installation makes window close quit without hiding`() {
        val failures = listOf(
            null,
            AWTException("Shell rejected the icon"),
            UnsupportedOperationException("No desktop shell"),
            SecurityException("Tray access denied"),
        )
        failures.forEach { failure ->
            var quitCount = 0
            val visibility = DesktopWindowVisibility(WindowState()) { quitCount++ }
            visibility.installTray { _, _ ->
                tryInstallDesktopTray {
                    if (failure != null) throw failure
                    null
                }
            }

            visibility.closeWindow()
            assertEquals(1, quitCount)
            assertTrue(visibility.isVisible)
        }
    }

    @Test
    fun `opening a visible minimized window requests focus again`() {
        val state = WindowState()
        val visibility = DesktopWindowVisibility(state) { }
        visibility.openWindow()
        state.isMinimized = true
        visibility.openWindow()
        assertTrue(visibility.isVisible)
        assertFalse(state.isMinimized)
        assertEquals(2, visibility.restoreRequest)
    }
}
