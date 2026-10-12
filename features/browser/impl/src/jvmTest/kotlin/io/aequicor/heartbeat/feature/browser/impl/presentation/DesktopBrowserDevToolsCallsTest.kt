package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBrowserDevToolsCallsTest {
    private val calls = DesktopBrowserDevToolsCalls(Log.tag("Test"))
    private val immediately: (() -> Unit) -> Unit = { it() }

    @Test
    fun `closed engine fails a call at once instead of leaving it waiting`() = runTest {
        var isExecuted = false
        val failure = assertFailsWith<IllegalStateException> {
            calls.call(immediately, isClosed = { true }) {
                isExecuted = true
                CompletableFuture()
            }
        }
        assertEquals("Browser is closed", failure.message)
        assertFalse(isExecuted)
    }

    @Test
    fun `failing dispatch fails only that call`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            calls.call(immediately, isClosed = { false }) { error("native rejected") }
        }
        assertEquals("Browser automation call failed", failure.message)
        assertEquals("{}", calls.call(immediately, isClosed = { false }) { CompletableFuture.completedFuture("{}") })
    }

    @Test
    fun `closing fails calls still waiting for a reply`() = runTest {
        val reply = CompletableFuture<String>()
        val pending = async {
            assertFailsWith<IllegalStateException> { calls.call(immediately, isClosed = { false }) { reply } }
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        calls.failAll()
        assertEquals("Browser is closed", pending.await().message)
        reply.complete("late")
    }

    @Test
    fun `cancelled caller cancels the native reply`() = runTest {
        val reply = CompletableFuture<String>()
        val pending = async { calls.call(immediately, isClosed = { false }) { reply } }
        runCurrent()
        pending.cancel()
        runCurrent()
        assertTrue(reply.isCancelled)
    }

    @Test
    fun `unavailable DevTools client is reported as not ready`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            calls.call(immediately, isClosed = { false }) { null }
        }
        assertEquals("Browser is not ready", failure.message)
    }
}
