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
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessSchedulerSnapshotTest {
    private val project = WorkspaceRef("project")
    private val checkout = WorkspaceRef("checkout")
    private val harness = runtimeRequest(1).harness.copy(scope = HarnessScope.Projects(setOf(project)))
    private val ready = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
    private val wake = HarnessTarget(dispatchSession, checkout)

    @Test
    fun `durable connections obey the eight active harness limit`() {
        val guard = harness.copy(id = HarnessId("guard"), name = HarnessName("zz_guard"))
        val earlier = (1..8).map { index ->
            harness.copy(id = HarnessId("earlier-$index"), name = HarnessName("early_$index"))
        }
        val all = earlier + guard
        val library = ready.copy(
            harnesses = all.map(::HarnessEntry),
            attachments = mapOf(wake.session to all.mapTo(mutableSetOf()) { it.id }),
        )
        val snapshot = HarnessSchedulerSnapshot(library, 1)
        assertEquals(false, snapshot.allows(guard.id, wake))
        assertEquals(
            true,
            snapshot.copy(library = library.copy(harnesses = all.drop(1).map(::HarnessEntry)))
                .allows(guard.id, wake),
        )
    }

    @Test
    fun `legacy project scope never admits a wake without a connection`() {
        val snapshot = HarnessSchedulerSnapshot(ready, 1)
        assertEquals(false, snapshot.allows(harness.id, wake))
        val connected = snapshot.copy(library = ready.copy(attachments = mapOf(wake.session to setOf(harness.id))))
        assertEquals(true, connected.allows(harness.id, wake))
        assertEquals(false, connected.copy(epoch = null).allows(harness.id, wake))
    }

    @Test
    fun `durable connection admits helper and pending removal revokes it`() {
        val snapshot = HarnessSchedulerSnapshot(ready, 1)
        assertEquals(false, snapshot.allows(harness.id, wake))
        val connected = ready.copy(attachments = mapOf(wake.session to setOf(harness.id)))
        assertEquals(true, snapshot.copy(library = connected).allows(harness.id, wake))
        val deleting = connected.copy(
            pending = mapOf(
                harness.id to HarnessMutation.Remove(
                    HarnessReceipt(RequestId("remove"), harness.id, harness.revision),
                    harness,
                ),
            ),
        )
        assertEquals(false, snapshot.copy(library = deleting).allows(harness.id, wake))
        assertEquals(false, snapshot.copy(library = connected.copy(isSuspended = true)).allows(harness.id, wake))
    }

    @Test
    fun `connection works even when project evidence is unavailable`() {
        val orphan = HarnessTarget(dispatchSession, WorkspaceRef("removed"))
        val profile = harness.copy(
            id = HarnessId("profile"),
            name = HarnessName("profile"),
            scope = HarnessScope.Profile,
        )
        val library = ready.copy(harnesses = listOf(HarnessEntry(harness), HarnessEntry(profile)))
        val snapshot = HarnessSchedulerSnapshot(library, 1)
        assertEquals(false, snapshot.allows(harness.id, orphan))
        assertEquals(false, snapshot.allows(profile.id, orphan))
        val connected = library.copy(attachments = mapOf(orphan.session to setOf(profile.id)))
        assertEquals(true, snapshot.copy(library = connected).allows(profile.id, orphan))
        assertEquals(false, snapshot.copy(library = connected).allows(harness.id, orphan))
    }
}
