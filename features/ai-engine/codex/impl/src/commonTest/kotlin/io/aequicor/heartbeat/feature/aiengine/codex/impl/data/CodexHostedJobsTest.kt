@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexHostedJobsTest {
    @Test
    fun `revocation before admission prevents creating a new parent`() = runTest {
        val jobs = CodexHostedJobs(backgroundScope)
        val turn = TurnId("closed")
        jobs.revoke(turn)
        assertNull(jobs.parent(turn))
        assertTrue(jobs.drain(turn))
    }

    @Test
    fun `normal revocation retains a child with delayed cleanup for explicit drain`() = runTest {
        val jobs = CodexHostedJobs(backgroundScope, timeoutMillis = 100)
        val turn = TurnId("old")
        val parent = assertNotNull(jobs.parent(turn))
        val cleanup = CompletableDeferred<Unit>()
        val child = CoroutineScope(backgroundScope.coroutineContext + parent).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        runCurrent()
        jobs.revoke(turn)
        assertFalse(parent.isActive)
        assertNotNull(jobs.parent(TurnId("new")))
        assertFalse(jobs.drain(turn))
        assertNotNull(jobs.lifetime(turn))
        assertFalse(child.isCompleted)
        cleanup.complete(Unit)
        assertTrue(jobs.drain(turn))
        assertTrue(child.isCompleted)
        assertNull(jobs.lifetime(turn))
        assertNull(jobs.parent(turn))
    }

    @Test
    fun `cancelled drain retains a noncancellable child and the next wait still joins it`() = runTest {
        val jobs = CodexHostedJobs(backgroundScope)
        val turn = TurnId("turn")
        val cleanup = CompletableDeferred<Unit>()
        CoroutineScope(backgroundScope.coroutineContext + assertNotNull(jobs.parent(turn))).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        runCurrent()
        val first = async { jobs.drain(turn) }
        runCurrent()
        first.cancelAndJoin()
        val retry = async { jobs.drain(turn) }
        runCurrent()
        assertFalse(retry.isCompleted)
        cleanup.complete(Unit)
        assertTrue(retry.await())
    }
}
