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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HarnessActiveSnapshotTest {
    private val workspace = WorkspaceRef("project")
    private val profile = harness.copy(scope = HarnessScope.Profile)
    private val project = harness.copy(
        id = HarnessId("project"),
        name = HarnessName("project"),
        scope = HarnessScope.Projects(setOf(workspace)),
    )
    private val ready = HarnessState.Ready(listOf(HarnessEntry(profile), HarnessEntry(project)))
    private val snapshot = HarnessActiveSnapshot(true, ready)

    @Test
    fun `legacy scopes never activate a chat without a connection`() {
        assertEquals(emptyList(), snapshot.select(workspace, dispatchSession))
        assertEquals(emptyList(), snapshot.select(null, null))
        assertEquals(emptyList(), snapshot.applicable(workspace, dispatchSession))
    }

    @Test
    fun `explicit connection works without project evidence and only for its session`() {
        val connected = snapshot.copy(library = ready.copy(
            attachments = mapOf(dispatchSession to setOf(profile.id, project.id)),
        ))
        assertEquals(listOf(project, profile), connected.select(workspace, dispatchSession))
        assertEquals(listOf(project, profile), connected.select(null, dispatchSession))
        assertEquals(emptyList(), connected.select(workspace, null))
        assertEquals(emptyList(), connected.copy(isEnabled = false).select(null, dispatchSession))
        assertEquals(emptyList(), connected.copy(library = ready.copy(isSuspended = true)).select(null, dispatchSession))
    }

    @Test
    fun `unavailable library differs from a known empty library`() {
        assertNull(snapshot.copy(library = null).select(null, null))
        assertNull(snapshot.copy(library = HarnessState.Idle()).select(null, null))
        assertFailsWith<IllegalStateException> {
            snapshot.copy(library = HarnessState.Failed()).select(null, null)
        }
        assertEquals(emptyList(), snapshot.copy(library = HarnessState.Ready()).select(workspace, null))
    }

    @Test
    fun `pending removal stops a connected harness immediately`() {
        val connected = ready.copy(attachments = mapOf(dispatchSession to setOf(profile.id)))
        val receipt = HarnessReceipt(RequestId("change"), profile.id, profile.revision)
        val saving = connected.copy(
            pending = mapOf(profile.id to HarnessMutation.Save(receipt, profile.copy(isEnabled = false), false)),
        )
        assertEquals(listOf(profile), snapshot.copy(library = saving).select(null, dispatchSession))
        val removing = connected.copy(pending = mapOf(profile.id to HarnessMutation.Remove(receipt, profile)))
        assertEquals(emptyList(), snapshot.copy(library = removing).select(null, dispatchSession))
        val disabled = connected.copy(harnesses = listOf(HarnessEntry(profile.copy(isEnabled = false))))
        assertEquals(emptyList(), snapshot.copy(library = disabled).select(null, dispatchSession))
    }

    @Test
    fun `connection selection remains bounded in immutable name order`() {
        val entries = (0..9).reversed().map {
            HarnessEntry(profile.copy(id = HarnessId("$it"), name = HarnessName("h_$it")))
        }
        val crowded = snapshot.copy(library = ready.copy(
            harnesses = entries,
            attachments = mapOf(dispatchSession to entries.mapTo(mutableSetOf()) { it.harness.id }),
        ))
        assertEquals((0..7).map { "h_$it" }, crowded.select(null, dispatchSession)?.map { it.name.value })
        assertEquals((0..9).map { "h_$it" }, crowded.applicable(null, dispatchSession)?.map { it.name.value })
    }
}
