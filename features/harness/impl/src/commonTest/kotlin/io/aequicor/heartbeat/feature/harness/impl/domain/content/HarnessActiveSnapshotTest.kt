package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HarnessActiveSnapshotTest {
    private val project = WorkspaceRef("project")
    private val checkout = WorkspaceRef("checkout")
    private val profile = harness.copy(scope = HarnessScope.Profile)
    private val ready = HarnessState.Ready(listOf(HarnessEntry(profile)))
    private val snapshot = HarnessActiveSnapshot(true, ready, null, null)

    @Test
    fun `mobile content and policy work without runtime or project catalog`() {
        assertEquals(listOf(profile), snapshot.select(checkout, dispatchSession))
        assertEquals(listOf(profile), snapshot.select(null, null))
        assertEquals(emptyList(), snapshot.copy(isEnabled = false).select(null, null))
        assertEquals(emptyList(), snapshot.copy(library = ready.copy(isSuspended = true)).select(null, null))
    }

    @Test
    fun `unavailable library differs from a known empty library`() {
        assertNull(snapshot.copy(library = null).select(null, null))
        assertNull(snapshot.copy(library = HarnessState.Idle()).select(null, null))
        assertFailsWith<IllegalStateException> {
            snapshot.copy(library = HarnessState.Failed()).select(null, null)
        }
        assertEquals(emptyList(), snapshot.copy(library = HarnessState.Ready()).select(checkout, null))
    }

    @Test
    fun `restored worktree mapping takes precedence over promoted checkout`() {
        val scoped = profile.copy(scope = HarnessScope.Projects(setOf(project)))
        val promoted = scoped.copy(
            id = HarnessId("promoted"), name = HarnessName("promoted"),
            scope = HarnessScope.Projects(setOf(checkout)),
        )
        val pending = snapshot.copy(
            library = ready.copy(harnesses = listOf(scoped, promoted).map(::HarnessEntry)),
            projects = setOf(checkout),
        )
        assertNull(pending.select(checkout, dispatchSession))
        val restored = pending.copy(
            worktrees = WorktreeState.Ready(
                mapOf("chat" to WorktreeTask("chat", project, executionWorkspace = checkout)),
            ),
        )
        assertEquals(listOf(scoped), restored.select(checkout, dispatchSession))
        assertEquals(emptyList(), restored.select(null, dispatchSession))
        assertNull(restored.select(WorkspaceRef("unknown"), dispatchSession))
    }

    @Test
    fun `attachments require the exact session but can resolve without project evidence`() {
        val scoped = harness.copy(scope = HarnessScope.Projects(setOf(project)))
        val attached = snapshot.copy(
            library = ready.copy(
                harnesses = listOf(HarnessEntry(scoped)),
                attachments = mapOf(dispatchSession to setOf(scoped.id)),
            ),
        )
        assertEquals(listOf(scoped), attached.select(checkout, dispatchSession))
        assertNull(attached.select(checkout, null))
        assertEquals(emptyList(), attached.select(null, null))
    }

    @Test
    fun `only committed values apply while pending removal stops contribution immediately`() {
        val receipt = HarnessReceipt(RequestId("change"), profile.id, profile.revision)
        val saving = ready.copy(
            pending = mapOf(profile.id to HarnessMutation.Save(receipt, profile.copy(isEnabled = false), false)),
        )
        assertEquals(listOf(profile), snapshot.copy(library = saving).select(null, null))
        val removing = ready.copy(pending = mapOf(profile.id to HarnessMutation.Remove(receipt, profile)))
        assertEquals(emptyList(), snapshot.copy(library = removing).select(null, null))
        val disabled = ready.copy(harnesses = listOf(HarnessEntry(profile.copy(isEnabled = false))))
        assertEquals(emptyList(), snapshot.copy(library = disabled).select(null, null))
    }

    @Test
    fun `selection remains bounded in immutable name order`() {
        val entries = (0..9).reversed().map {
            HarnessEntry(profile.copy(id = HarnessId("$it"), name = HarnessName("h_$it")))
        }
        val selected = snapshot.copy(library = ready.copy(harnesses = entries)).select(null, null)
        assertEquals((0..7).map { "h_$it" }, selected?.map { it.name.value })
    }
}
