package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.window.WindowState
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopReopenHandlerTest {
    @Test
    fun `Dock reopen restores the hidden minimized window on the event thread`() {
        val state = WindowState()
        val visibility = DesktopWindowVisibility(state) { error("Reopen must not quit") }
        val listener = DesktopReopenHandler({
            assertTrue(EventQueue.isDispatchThread())
            visibility.openWindow()
        }, {})
        EventQueue.invokeAndWait {
            visibility.installTray { _, _ -> AutoCloseable {} }
            visibility.closeWindow()
            state.isMinimized = true
        }
        listener.appReopened(null)
        EventQueue.invokeAndWait {
            assertTrue(visibility.isVisible)
            assertFalse(state.isMinimized)
            assertEquals(1, visibility.restoreRequest)
            listener.close()
            visibility.close()
        }
    }

    @Test
    fun `disposing the handler invalidates queued reopen events and unregisters once`() {
        var opens = 0
        var removals = 0
        val listener = DesktopReopenHandler({ opens++ }, { removals++ })
        EventQueue.invokeAndWait {
            listener.appReopened(null)
            listener.close()
            listener.close()
        }
        EventQueue.invokeAndWait { assertEquals(0, opens) }
        assertEquals(1, removals)
    }
}
