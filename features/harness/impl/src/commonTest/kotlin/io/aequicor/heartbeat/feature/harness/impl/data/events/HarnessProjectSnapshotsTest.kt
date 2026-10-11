package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HarnessProjectSnapshotsTest {
    @Test
    fun `catalog failure revokes project proof without cancelling enabled owner`() = runTest {
        val snapshots = HarnessProjectSnapshots()
        val fail = CompletableDeferred<Unit>()
        val project = LocalWorkspace(WorkspaceRef("project"), "Private project")
        snapshots.observe(
            backgroundScope,
            flow {
                emit(listOf(project))
                fail.await()
                throw IllegalStateException("private catalog failure")
            },
        )
        assertEquals(setOf(project.ref), snapshots.projects.value)
        fail.complete(Unit)
        runCurrent()
        assertNull(snapshots.projects.value)
        assertTrue(backgroundScope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun `failed catalog is collected again with growing delay`() = runTest {
        val snapshots = HarnessProjectSnapshots()
        val project = LocalWorkspace(WorkspaceRef("project"), "Private project")
        var collections = 0
        snapshots.observe(
            backgroundScope,
            flow {
                collections++
                if (collections <= 2) throw IllegalStateException("private catalog failure")
                emit(listOf(project))
                awaitCancellation()
            },
        )
        assertNull(snapshots.projects.value)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, collections)
        assertNull(snapshots.projects.value)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(3, collections)
        assertEquals(setOf(project.ref), snapshots.projects.value)
    }

    @Test
    fun `stopping project observer revokes last trusted snapshot`() = runTest {
        val snapshots = HarnessProjectSnapshots()
        val branch = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        snapshots.observe(
            branch,
            flow {
                emit(emptyList())
                awaitCancellation()
            },
        )
        assertEquals(emptySet(), snapshots.projects.value)
        branch.cancel()
        runCurrent()
        assertNull(snapshots.projects.value)
    }

    @Test
    fun `backoff survives emissions and resets only after a stable collection`() = runTest {
        val snapshots = HarnessProjectSnapshots(testScheduler.timeSource)
        val project = LocalWorkspace(WorkspaceRef("project"), "Private project")
        var collections = 0
        snapshots.observe(
            backgroundScope,
            flow {
                collections++
                emit(listOf(project))
                if (collections == 3) delay(5.minutes)
                throw IllegalStateException("private catalog failure")
            },
        )
        assertEquals(1, collections)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, collections)
        // An emission right before failing does not forgive the streak: the next delay doubles.
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, collections)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(3, collections)
        // After a healthy period the next failure retries after the base delay again.
        advanceTimeBy(5.minutes)
        runCurrent()
        assertNull(snapshots.projects.value)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(4, collections)
    }
}
