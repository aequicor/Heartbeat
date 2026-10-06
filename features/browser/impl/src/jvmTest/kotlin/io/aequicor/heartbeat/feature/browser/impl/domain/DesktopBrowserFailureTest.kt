package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBrowserFailureTest {
    @Test
    fun `native diagnostics preserve stack without leaking pages through nested exceptions`() {
        val failure = IllegalStateException(
            "https://private.example/?token=secret",
            IllegalArgumentException("private title"),
        ).apply {
            addSuppressed(IllegalStateException("/Users/private/browser.db"))
        }

        val safe = failure.desktopBrowserFailure()

        assertTrue(safe.message.orEmpty().contains("IllegalStateException"))
        assertFalse(safe.stackTraceToString().contains("secret"))
        assertFalse(safe.stackTraceToString().contains("private"))
        assertNull(safe.cause)
        assertTrue(safe.suppressed.isEmpty())
        assertContentEquals(failure.stackTrace, safe.stackTrace)
    }
}
