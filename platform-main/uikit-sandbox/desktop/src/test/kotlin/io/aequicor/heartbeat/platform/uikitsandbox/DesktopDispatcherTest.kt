package io.aequicor.heartbeat.platform.uikitsandbox

import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDispatcherTest {
    @Test
    fun `main dispatcher recognizes the AWT event dispatch thread`() {
        assertFalse(SwingUtilities.isEventDispatchThread())
        assertTrue(Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext))

        SwingUtilities.invokeAndWait {
            assertTrue(SwingUtilities.isEventDispatchThread())
            assertFalse(Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext))
        }
    }
}
