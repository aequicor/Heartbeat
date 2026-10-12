package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexRecheckAttemptsTest {
    @Test
    fun `an earlier attempt finishing does not satisfy a wait for a later one`() = runTest {
        val attempts = CodexRecheckAttempts(FakeScope(backgroundScope))
        val earlier = attempts.started()
        val own = attempts.started()
        assertEquals(own, attempts.latest)
        val wait = async { attempts.awaitFinished(own) }
        runCurrent()
        attempts.finished(earlier)
        runCurrent()
        assertFalse(wait.isCompleted)
        attempts.finished(own)
        runCurrent()
        assertTrue(wait.isCompleted)
    }

    @Test
    fun `closing the session releases waiters`() = runTest {
        val scope = FakeScope(backgroundScope)
        val attempts = CodexRecheckAttempts(scope)
        val wait = async { attempts.awaitFinished(attempts.started()) }
        runCurrent()
        assertFalse(wait.isCompleted)
        scope.close()
        runCurrent()
        assertTrue(wait.isCompleted)
    }
}
