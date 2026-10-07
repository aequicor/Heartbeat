package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
}
