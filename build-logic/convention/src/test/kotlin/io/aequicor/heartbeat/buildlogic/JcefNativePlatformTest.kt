package io.aequicor.heartbeat.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JcefNativePlatformTest {
    @Test
    fun `linux x64 aliases select the published JNI identifier`() {
        assertEquals("linux-amd64", jcefNativePlatform("Linux", "amd64"))
        assertEquals("linux-amd64", jcefNativePlatform("Linux", "x86_64"))
    }

    @Test
    fun `unsupported Linux architectures never receive x64 JNI`() {
        listOf("aarch64", "arm64", "x86", "riscv64").forEach {
            assertNull(jcefNativePlatform("Linux", it))
        }
        assertNull(jcefNativePlatform("FreeBSD", "amd64"))
    }

    @Test
    fun `existing desktop hosts retain their platform identifiers`() {
        assertEquals("macosx-arm64", jcefNativePlatform("Mac OS X", "aarch64"))
        assertEquals("macosx-amd64", jcefNativePlatform("Mac OS X", "x86_64"))
        assertEquals("windows-arm64", jcefNativePlatform("Windows 11", "aarch64"))
        assertEquals("windows-amd64", jcefNativePlatform("Windows Server 2025", "amd64"))
    }
}
