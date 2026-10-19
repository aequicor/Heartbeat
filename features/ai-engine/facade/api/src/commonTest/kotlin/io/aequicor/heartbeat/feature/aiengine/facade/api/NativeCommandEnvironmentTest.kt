package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativeCommandEnvironmentTest {
    @Test
    fun `environment evidence copies its source and redacts credentials`() {
        val supplied = mutableMapOf("PATH" to "/trusted/bin", "API_KEY" to "private-credential")
        val captured = NativeCommandEnvironment(supplied)
        supplied["PATH"] = "/changed/bin"
        supplied.clear()

        assertEquals("/trusted/bin", captured.environment["PATH"])
        assertEquals("private-credential", captured.environment["API_KEY"])
        assertFalse("private-credential" in captured.toString())
        assertFalse("/trusted/bin" in captured.toString())
    }
}
