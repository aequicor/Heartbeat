package io.aequicor.heartbeat.core.logging

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDataNameTest {
    @Test
    fun `the data name is Heartbeat unless a build names another`() {
        assertEquals("Heartbeat", desktopDataName(null))
        assertEquals("Heartbeat", desktopDataName(" "))
        assertEquals("HeartbeatDev", desktopDataName("HeartbeatDev"))
        assertEquals("heartbeatdev", desktopDataNameLowercase("HeartbeatDev"))
    }

    @Test
    fun `a name that is not a plain directory name is ignored`() {
        assertEquals("Heartbeat", desktopDataName("../x"))
        assertEquals("Heartbeat", desktopDataName("a/b"))
    }

    @Test
    fun `the macOS log directory follows the data name`() {
        val logs = desktopLogDirectory(os = "Mac OS X", home = "/Users/u", localAppData = null, stateHome = null)
        assertEquals(Path.of("/Users/u", "Library", "Logs", desktopDataName()), logs)
    }
}
