package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BootstrapLayoutTest {
    @Test
    fun platformPathsMatchJpackageImages() {
        val windows = BootstrapLayout.forPlatform("windows", "release")
        assertEquals("runtime", windows.runtime)
        assertEquals("app/resources/seed", windows.seed)
        assertEquals("Heartbeat.exe", windows.executable)
        val linux = BootstrapLayout.forPlatform("linux", "release")
        assertEquals("lib/runtime", linux.runtime)
        assertEquals("lib/app/resources/seed", linux.seed)
        assertEquals("bin/Heartbeat", linux.executable)
        val macos = BootstrapLayout.forPlatform("macos", "release")
        assertEquals("Heartbeat.app/Contents/runtime", macos.runtime)
        assertEquals("Heartbeat.app/Contents/app/resources/seed", macos.seed)
        assertEquals("Heartbeat.app/Contents/MacOS/Heartbeat", macos.executable)
    }

    @Test
    fun channelsHaveDistinctMacBundles() {
        assertEquals(
            "HeartbeatDev.app/Contents/MacOS/HeartbeatDev",
            BootstrapLayout.forPlatform("macos", "dev").executable,
        )
        assertEquals(
            "HeartbeatBeta.app/Contents/MacOS/HeartbeatBeta",
            BootstrapLayout.forPlatform("macos", "beta").executable,
        )
        assertFailsWith<IllegalStateException> { BootstrapLayout.forPlatform("macos", "../unsafe") }
        assertFailsWith<IllegalStateException> { BootstrapLayout.forPlatform("unknown", "release") }
    }
}
