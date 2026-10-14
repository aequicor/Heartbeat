package io.aequicor.heartbeat.platform.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HarnessWindowRuntimeTest {
    @Test
    fun `linux packaging does not ask for unavailable native caption services`() {
        verifyHarnessWindowRuntime("Linux") { error("Linux must use standard window decorations") }
    }

    @Test
    fun `macos and windows packaging still rejects a runtime without native captions`() {
        listOf("Mac OS X", "Windows 11").forEach { os ->
            val failure = assertFailsWith<IllegalStateException> {
                verifyHarnessWindowRuntime(os) { false }
            }
            assertEquals("HarnessProbeWindowRuntimeUnavailable", failure.message)
        }
    }

    @Test
    fun `supported native caption services pass the packaging probe`() {
        var checks = 0
        listOf("Mac OS X", "Windows 11").forEach { os ->
            verifyHarnessWindowRuntime(os) {
                checks++
                true
            }
        }
        assertEquals(2, checks)
    }
}
