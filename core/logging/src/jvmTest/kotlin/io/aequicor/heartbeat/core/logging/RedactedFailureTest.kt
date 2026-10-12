package io.aequicor.heartbeat.core.logging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedactedFailureTest {
    @Test
    fun `redacted failure keeps type and stack but drops message and causes`() {
        val failure = IllegalArgumentException("https://intranet.example/secret?q=1", IllegalStateException("cause"))
        failure.addSuppressed(RuntimeException("suppressed"))
        val redacted = failure.redactedFailure()
        assertEquals(IllegalArgumentException::class.java.name, redacted.message)
        assertContentEquals(failure.stackTrace, redacted.stackTrace)
        assertNull(redacted.cause)
        assertTrue(redacted.suppressed.isEmpty())
        assertFalse(redacted.stackTraceToString().contains("intranet"))
        assertFalse(redacted.stackTraceToString().contains("suppressed"))
    }
}
