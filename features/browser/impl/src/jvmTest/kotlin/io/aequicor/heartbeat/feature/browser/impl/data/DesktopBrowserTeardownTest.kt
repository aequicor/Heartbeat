package io.aequicor.heartbeat.feature.browser.impl.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserTeardownTest {
    @Test
    fun `timeout leaves native teardown owned and a later retry can finish`() = runTest {
        val closed = CompletableDeferred<Unit>()
        val teardown = backgroundScope.async { closed.await() }
        assertFailsWith<IllegalStateException> { awaitBrowserTeardown(teardown, timeoutMillis = 10) }
        assertFalse(teardown.isCancelled)
        closed.complete(Unit)
        awaitBrowserTeardown(teardown)
        assertTrue(teardown.isCompleted)
    }

    @Test
    fun `cancelling the caller does not release a still running native teardown`() = runTest {
        val closed = CompletableDeferred<Unit>()
        val teardown = backgroundScope.async { closed.await() }
        val caller = backgroundScope.async { awaitBrowserTeardown(teardown) }
        runCurrent()
        caller.cancelAndJoin()
        assertFalse(teardown.isCancelled)
        assertFalse(teardown.isCompleted)
        closed.complete(Unit)
        awaitBrowserTeardown(teardown)
    }
}
