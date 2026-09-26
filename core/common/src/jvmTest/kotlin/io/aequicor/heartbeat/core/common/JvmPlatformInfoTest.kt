package io.aequicor.heartbeat.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmPlatformInfoTest {

    @Test
    fun host_is_resolved_from_os_name() {
        assertEquals(HostPlatform.MacOs, JvmPlatformInfo.hostOf("Mac OS X"))
        assertEquals(HostPlatform.Windows, JvmPlatformInfo.hostOf("Windows 11"))
        assertEquals(HostPlatform.Linux, JvmPlatformInfo.hostOf("Linux"))
    }
}
