package io.aequicor.heartbeat.platform.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopLoggingTest {
    @Test
    fun `launch line names the train that retrace reads`() {
        assertEquals("Launch: train=t-2026.10.27 version=1.4.2", trainLogLine("t-2026.10.27", "1.4.2"))
    }

    @Test
    fun `launch without launcher or version is stated explicitly`() {
        assertEquals("Launch: train=none version=dev", trainLogLine(null, null))
        assertEquals("Launch: train=none version=dev", trainLogLine(" ", ""))
    }
}
