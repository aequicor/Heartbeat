@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_query_native_machine
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsHostTest {
    @Test
    fun emulatedX64UsesTheNativeArm64Payload() {
        val machine = hb_query_native_machine(
            staticCFunction { _, process, native ->
                process?.pointed?.value = 0x8664u
                native?.pointed?.value = 0xaa64u
                1
            },
        )
        assertEquals("windows-arm64", windowsPayloadTarget(machine.toInt()))
    }

    @Test
    fun nativeX64KeepsTheEmbeddedPayload() {
        val machine = hb_query_native_machine(
            staticCFunction { _, process, native ->
                process?.pointed?.value = 0u
                native?.pointed?.value = 0x8664u
                1
            },
        )
        assertEquals("windows-x64", windowsPayloadTarget(machine.toInt()))
    }

    @Test
    fun failedNativeQueryDoesNotSilentlySelectX64() {
        val machine = hb_query_native_machine(staticCFunction { _, _, _ -> 0 })
        assertFailsWith<IllegalStateException> { windowsPayloadTarget(machine.toInt()) }
    }

    @Test
    fun actualWindowsHostMatchesRunnerArchitectureWhenAvailable() {
        val expected = when (launcherEnvironment("RUNNER_ARCH")) {
            "ARM64" -> "windows-arm64"
            "X64" -> "windows-x64"
            else -> return
        }
        assertEquals(expected, launcherHostTarget())
    }
}
