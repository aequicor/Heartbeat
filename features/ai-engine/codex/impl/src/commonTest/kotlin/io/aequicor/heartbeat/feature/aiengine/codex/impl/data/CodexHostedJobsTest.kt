@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
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
import kotlin.test.assertEquals
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
    fun `profile drains retain only pending parents and forget them once cleanup ends`() = runTest {
        val jobs = CodexHostedJobs(backgroundScope, timeoutMillis = 100)
        val turn = TurnId("turn")
        val cleanup = CompletableDeferred<Unit>()
        CoroutineScope(backgroundScope.coroutineContext + assertNotNull(jobs.parent(turn))).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        assertNotNull(jobs.parent(TurnId("idle"))).cancel()
        runCurrent()
        assertEquals(setOf(turn), jobs.pending().keys)
        val drains = CodexHostedDrains()
        val ref = SessionRef(CodexEngine.Id, SessionSourceId("codex.local"), "thread")
        val route =
            ExecutionRoute(CodexEngine.Id, EngineBindingId("binding"), AuthSourceId("codex"), AuthRevision.Unknown)
        drains.retain(ref, route, "owner", jobs)
        assertFalse(drains.isEmpty)
        assertFalse(drains.drain(ref, route, "owner", turn))
        cleanup.complete(Unit)
        assertTrue(drains.drain(ref, route, "owner", turn))
        assertTrue(drains.isEmpty)
    }

    @Test
    fun `renewed login drains its own work and completed foreign entries never refuse`() = runTest {
        val drains = CodexHostedDrains()
        val ref = SessionRef(CodexEngine.Id, SessionSourceId("codex.local"), "thread")
        val turn = TurnId("turn")
        fun route(binding: String, revision: String) = ExecutionRoute(
            CodexEngine.Id,
            EngineBindingId(binding),
            AuthSourceId("codex"),
            AuthRevision.Known(revision),
        )
        val ownCleanup = CompletableDeferred<Unit>()
        val foreignCleanup = CompletableDeferred<Unit>()
        drains.retain(ref, route("binding", "old"), "owner", pendingJobs(turn, ownCleanup))
        val foreign = pendingJobs(turn, foreignCleanup)
        drains.retain(ref, route("foreign", "old"), "owner", foreign)
        foreign.revoke(turn)
        foreignCleanup.complete(Unit)
        runCurrent()
        // The renewed revision still waits for its own pending cleanup.
        assertFalse(drains.drain(ref, route("binding", "new"), "owner", turn))
        ownCleanup.complete(Unit)
        runCurrent()
        assertTrue(drains.drain(ref, route("binding", "new"), "owner", turn))
        assertTrue(drains.isEmpty)
    }

    private fun kotlinx.coroutines.test.TestScope.pendingJobs(
        turn: TurnId,
        cleanup: CompletableDeferred<Unit>,
    ): CodexHostedJobs {
        val jobs = CodexHostedJobs(backgroundScope, timeoutMillis = 100)
        CoroutineScope(backgroundScope.coroutineContext + assertNotNull(jobs.parent(turn))).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        runCurrent()
        return jobs
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
