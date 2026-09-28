package io.aequicor.heartbeat.feature.aiengine.acpinterface.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AcpCommandTest {
    @Test
    fun `process arguments and environment are redacted from diagnostics`() {
        val command = AcpCommand(
            executable = "/private/agent",
            arguments = listOf("--credential", "private-value"),
            environment = mapOf("PRIVATE_KEY" to "private-key"),
        )
        assertEquals("AcpCommand(<redacted>)", command.toString())
    }

    @Test
    fun `empty executable is rejected before process launch`() {
        assertFailsWith<IllegalArgumentException> { AcpCommand(" ") }
    }
}
