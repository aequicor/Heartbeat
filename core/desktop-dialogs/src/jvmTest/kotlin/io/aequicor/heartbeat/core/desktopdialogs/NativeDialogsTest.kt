package io.aequicor.heartbeat.core.desktopdialogs

import io.aequicor.heartbeat.core.common.HostPlatform
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeDialogsTest {
    @Test
    fun `windows shows the explorer dialog`() {
        assertIs<WindowsExplorerDialogs>(nativeDialogsFor(HostPlatform.Windows))
    }

    @Test
    fun `macos folders come from the finder panel property`() {
        val dialogs = nativeDialogsFor(HostPlatform.MacOs)
        assertIs<AwtNativeDialogs>(dialogs)
        assertTrue(dialogs.directoriesThroughAwtProperty)
    }

    @Test
    fun `hosts without a native folder dialog keep the development fallback`() {
        val dialogs = nativeDialogsFor(HostPlatform.Linux)
        assertIs<AwtNativeDialogs>(dialogs)
        assertFalse(dialogs.directoriesThroughAwtProperty)
    }
}
