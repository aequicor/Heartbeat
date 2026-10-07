package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.runtimeRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessSchedulerSnapshotTest {
    private val project = WorkspaceRef("project")
    private val checkout = WorkspaceRef("checkout")
    private val harness = runtimeRequest(1).harness.copy(scope = HarnessScope.Projects(setOf(project)))
    private val ready = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
    private val wake = HarnessTarget(dispatchSession, checkout)

    @Test
    fun `cold restart resolves original project without callback proof or promoted checkout fallback`() {
        val promoted = harness.copy(
            id = HarnessId("promoted"),
            name = HarnessName("promoted"),
            scope = HarnessScope.Projects(setOf(checkout)),
        )
        val library = ready.copy(harnesses = ready.harnesses + HarnessEntry(promoted))
        val snapshot = HarnessSchedulerSnapshot(library, WorktreeState.Loading, setOf(checkout), 1)
        assertEquals(null, snapshot.allows(harness.id, wake))
        assertEquals(null, snapshot.allows(promoted.id, wake))
        val restored = snapshot.copy(
            worktrees = WorktreeState.Ready(
                mapOf("chat" to WorktreeTask("chat", project, executionWorkspace = checkout)),
            ),
        )
        assertEquals(true, restored.allows(harness.id, wake))
        assertEquals(false, restored.allows(promoted.id, wake))
        assertEquals(false, restored.copy(epoch = null).allows(harness.id, wake))
    }

    @Test
    fun `durable attachment admits helper while owner context alone grants no affiliation`() {
        val attached = harness.copy(scope = HarnessScope.Attached)
        val library = ready.copy(harnesses = listOf(HarnessEntry(attached)))
        val snapshot = HarnessSchedulerSnapshot(library, WorktreeState.Ready(), setOf(checkout), 1)
        assertEquals(false, snapshot.allows(harness.id, wake))
        val restored = snapshot.copy(library = library.copy(attachments = mapOf(wake.session to setOf(harness.id))))
        assertEquals(true, restored.allows(harness.id, wake))
        val deleting = library.copy(
            pending = mapOf(
                harness.id to HarnessMutation.Remove(
                    HarnessReceipt(RequestId("remove"), harness.id, harness.revision),
                    harness,
                ),
            ),
        )
        assertEquals(false, restored.copy(library = deleting).allows(harness.id, wake))
        assertEquals(false, restored.copy(library = library.copy(isSuspended = true)).allows(harness.id, null))
    }
}
