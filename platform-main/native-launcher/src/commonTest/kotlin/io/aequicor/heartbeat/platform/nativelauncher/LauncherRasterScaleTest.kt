package io.aequicor.heartbeat.platform.nativelauncher

import kotlin.test.Test
import kotlin.test.assertEquals

class LauncherRasterScaleTest {
    @Test
    fun normalWindowsKeepFourSamplesAndLargeScreensBoundOverhead() {
        assertEquals(4, launcherRasterScale(1024, 768))
        assertEquals(2, launcherRasterScale(2560, 1440))
        assertEquals(1, launcherRasterScale(3840, 2160))
        assertEquals(1, launcherRasterScale(7680, 4320))
    }
}
