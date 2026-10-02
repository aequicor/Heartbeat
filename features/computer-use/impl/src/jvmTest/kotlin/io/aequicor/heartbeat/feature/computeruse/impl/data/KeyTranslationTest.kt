package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyTranslationTest {
    @Test
    fun `meta key becomes the windows key on windows`() {
        assertEquals(KeyEvent.VK_WINDOWS, injectableKeyCode(KeyEvent.VK_META, HostPlatform.Windows))
    }

    @Test
    fun `meta key stays itself on macos and linux`() {
        assertEquals(KeyEvent.VK_META, injectableKeyCode(KeyEvent.VK_META, HostPlatform.MacOs))
        assertEquals(KeyEvent.VK_META, injectableKeyCode(KeyEvent.VK_META, HostPlatform.Linux))
    }

    @Test
    fun `other key codes pass through untouched`() {
        for (code in listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_A, KeyEvent.VK_WINDOWS, KeyEvent.VK_F5)) {
            assertEquals(code, injectableKeyCode(code, HostPlatform.Windows))
            assertEquals(code, injectableKeyCode(code, HostPlatform.MacOs))
        }
    }
}
