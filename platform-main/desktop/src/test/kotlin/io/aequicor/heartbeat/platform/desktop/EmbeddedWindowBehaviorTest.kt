package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.platform.win32.WinUser
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseSuppressionReason
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddedWindowBehaviorTest {
    @Test
    fun `activating a maximized window keeps its placement while minimized windows restore`() {
        assertEquals(WinUser.SW_SHOWNA, embeddedOpenCommand(isMinimized = false))
        assertEquals(WinUser.SW_RESTORE, embeddedOpenCommand(isMinimized = true))
    }

    @Test
    fun `activation while capture is suppressed waits for release without repeating old requests`() {
        assertFalse(shouldOpenEmbeddedWindow(isCaptureSuppressed = true, request = 2, applied = 1))
        assertTrue(shouldOpenEmbeddedWindow(isCaptureSuppressed = false, request = 2, applied = 1))
        assertFalse(shouldOpenEmbeddedWindow(isCaptureSuppressed = false, request = 2, applied = 2))
    }

    @Test
    fun `intro consumes keyboard until reveal finishes and never executes shortcuts behind a modal`() {
        var shortcuts = 0
        fun shortcut(): Boolean {
            shortcuts++
            return true
        }
        assertTrue(embeddedConsumesKey(true, false, ::shortcut))
        assertEquals(0, shortcuts)
        assertFalse(embeddedConsumesKey(false, true, ::shortcut))
        assertEquals(0, shortcuts)
        assertTrue(embeddedConsumesKey(false, false, ::shortcut))
        assertEquals(1, shortcuts)
    }

    @Test
    fun `quiet cover requests no animation frames until native reveal`() = runTest {
        val frames = mutableListOf<Long>()
        var requested = 0
        animateEmbeddedIntro(null, 100, {
            requested++
            100
        }, frames::add)
        assertEquals(0, requested)
        assertTrue(frames.isEmpty())

        val times = ArrayDeque(listOf(100L, 1300L, 1900L))
        animateEmbeddedIntro(100, 100, {
            requested++
            times.removeFirst()
        }, frames::add)
        assertEquals(listOf(100L, 1300L, 1900L), frames)
        assertEquals(3, requested)
    }

    @Test
    fun `capture lease cannot show a window hidden to tray during capture`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        EventQueue.invokeAndWait {
            val restored = mutableListOf<Boolean>()
            val suppression = mutableListOf<Boolean>()
            var isVisible = true
            val frame = Frame()
            val presentation = DesktopCapturePresentation(
                frame,
                ComputerUseScreenOverlay(),
                isWindowVisible = { isVisible },
                suppressorOverride = CaptureSuppressor { _ ->
                    NativeCaptureSuppression { visible -> restored.add(visible) }
                },
                onSuppressionChange = { suppression.add(it) },
            )
            try {
                val lease = presentation.suppress(ComputerUseSuppressionReason.PointerInput)
                isVisible = false
                lease.close()
                assertTrue(restored.isEmpty())
                isVisible = true
                presentation.onVisibilityChanged()
                presentation.onVisibilityChanged()
                assertEquals(listOf(true), restored)
                presentation.suppress(ComputerUseSuppressionReason.PointerInput).close()
                assertEquals(listOf(true, true), restored)
                assertEquals(listOf(true, false, true, false), suppression)
            } finally {
                presentation.close()
                frame.dispose()
            }
        }
    }

    @Test
    fun `hidden capture snapshot survives another capture and is discarded on close`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        EventQueue.invokeAndWait {
            val restored = mutableListOf<Boolean>()
            var suppressions = 0
            var isVisible = true
            val frame = Frame()
            val presentation = DesktopCapturePresentation(
                frame,
                ComputerUseScreenOverlay(),
                isWindowVisible = { isVisible },
                suppressorOverride = CaptureSuppressor { _ ->
                    suppressions++
                    NativeCaptureSuppression { visible -> restored.add(visible) }
                },
            )
            try {
                val first = presentation.suppress(ComputerUseSuppressionReason.PointerInput)
                isVisible = false
                first.close()
                presentation.suppress(ComputerUseSuppressionReason.PointerInput).close()
                assertEquals(1, suppressions)
                assertTrue(restored.isEmpty())
                presentation.close()
                isVisible = true
                presentation.onVisibilityChanged()
                assertEquals(listOf(false), restored)
            } finally {
                presentation.close()
                frame.dispose()
            }
        }
    }
}
