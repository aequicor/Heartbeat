package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLoad
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessSessionProofsTest {
    private val harness = runtimeRequest(1).harness.copy(scope = HarnessScope.Profile)

    @Test
    fun `legacy profile and project scopes do not grant session admission`() {
        val project = harness.copy(
            id = HarnessId("project"),
            name = HarnessName("project"),
            scope = HarnessScope.Projects(setOf(PROJECT)),
        )
        val library = HarnessState.Ready(
            listOf(HarnessEntry(harness), HarnessEntry(project)),
            isRuntimeAvailable = true,
        )
        val proofs = HarnessSessionProofs({ library })
        proofs.remember(CONTEXT)
        assertTrue(proofs.isResolved(dispatchSession))
        assertFalse(proofs.allows(harness.id, dispatchSession))
        assertFalse(proofs.restricts(project.id, dispatchSession))
    }

    @Test
    fun `only a committed connection to the exact session admits a harness`() {
        var library = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
        val proofs = HarnessSessionProofs({ library })
        proofs.remember(CONTEXT)
        assertFalse(proofs.allows(harness.id, dispatchSession))
        library = library.copy(attachments = mapOf(dispatchSession to setOf(harness.id)))
        assertTrue(proofs.allows(harness.id, dispatchSession))
        assertTrue(proofs.restricts(harness.id, dispatchSession))
        val other = dispatchSession.copy(nativeId = "other")
        proofs.remember(CONTEXT.copy(session = other))
        assertFalse(proofs.allows(harness.id, other))
    }

    @Test
    fun `pending removal and suspension revoke a connected harness immediately`() {
        var library = HarnessState.Ready(
            listOf(HarnessEntry(harness)),
            attachments = mapOf(dispatchSession to setOf(harness.id)),
            isRuntimeAvailable = true,
        )
        val proofs = HarnessSessionProofs({ library })
        proofs.remember(CONTEXT)
        assertTrue(proofs.allows(harness.id, dispatchSession))
        library = library.copy(
            pending = mapOf(
                harness.id to HarnessMutation.Remove(
                    HarnessReceipt(RequestId("remove"), harness.id, harness.revision),
                    harness,
                ),
            ),
        )
        assertFalse(proofs.allows(harness.id, dispatchSession))
        library = library.copy(pending = emptyMap(), isSuspended = true)
        assertFalse(proofs.allows(harness.id, dispatchSession))
    }

    @Test
    fun `an attached harness needs a trusted nonconflicting session route`() {
        val library = HarnessState.Ready(
            listOf(HarnessEntry(harness)),
            attachments = mapOf(dispatchSession to setOf(harness.id)),
            isRuntimeAvailable = true,
        )
        val proofs = HarnessSessionProofs({ library })
        assertFalse(proofs.isResolved(dispatchSession))
        assertFalse(proofs.allows(harness.id, dispatchSession))
        proofs.remember(CONTEXT)
        assertTrue(proofs.allows(harness.id, dispatchSession))
        proofs.remember(CONTEXT.copy(workspace = PROJECT))
        assertFalse(proofs.isResolved(dispatchSession))
        assertFalse(proofs.allows(harness.id, dispatchSession))
    }

    @Test
    fun `unknown library requires conservative tool decision only while scripts are published`() {
        var isPublished = false
        var library: HarnessState = HarnessState.Idle()
        val proofs = HarnessSessionProofs({ library }, { isPublished })
        proofs.remember(CONTEXT)
        for (state in listOf(HarnessState.Idle(), HarnessState.Loading(HarnessLoad(1, 0)), HarnessState.Failed())) {
            library = state
            isPublished = false
            assertTrue(proofs.isResolved(dispatchSession))
            assertFalse(proofs.allows(harness.id, dispatchSession))
            isPublished = true
            assertFalse(proofs.isResolved(dispatchSession))
        }
    }

    @Test
    fun `proof cache is bounded and evicts known routes before a conflict`() {
        val oldest = dispatchSession.copy(nativeId = "oldest")
        val library = HarnessState.Ready(
            listOf(HarnessEntry(harness)),
            attachments = mapOf(dispatchSession to setOf(harness.id), oldest to setOf(harness.id)),
            isRuntimeAvailable = true,
        )
        val proofs = HarnessSessionProofs({ library })
        proofs.remember(CONTEXT.copy(workspace = null))
        proofs.remember(CONTEXT.copy(workspace = PROJECT))
        proofs.remember(CONTEXT.copy(session = oldest, workspace = null))
        assertTrue(proofs.allows(harness.id, oldest))
        repeat(512) {
            proofs.remember(CONTEXT.copy(session = dispatchSession.copy(nativeId = "s$it"), workspace = null))
        }
        // The oldest known route was forgotten; its next trusted callback simply proves it again.
        assertFalse(proofs.isResolved(oldest))
        proofs.remember(CONTEXT.copy(session = oldest, workspace = null))
        assertTrue(proofs.allows(harness.id, oldest))
        // The older conflict survived every eviction and still cannot be overwritten by a single late route.
        proofs.remember(CONTEXT.copy(workspace = null))
        assertFalse(proofs.isResolved(dispatchSession))
        assertFalse(proofs.allows(harness.id, dispatchSession))
    }

    @Test
    fun `overflowing connections restrict without contributing`() {
        val earlier = (0..7).map { harness.copy(id = HarnessId("a$it"), name = HarnessName("a$it")) }
        val guard = harness.copy(id = HarnessId("z"), name = HarnessName("zz_guard"))
        val all = earlier + guard
        val library = HarnessState.Ready(
            all.map(::HarnessEntry),
            attachments = mapOf(dispatchSession to all.mapTo(mutableSetOf()) { it.id }),
            isRuntimeAvailable = true,
        )
        val proofs = HarnessSessionProofs({ library })
        proofs.remember(CONTEXT)
        assertTrue(earlier.all { proofs.allows(it.id, dispatchSession) })
        assertFalse(proofs.allows(guard.id, dispatchSession))
        assertTrue(proofs.restricts(guard.id, dispatchSession))
    }

    @Test
    fun `session naming and another owner never grant a connection`() {
        val library = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
        val proofs = HarnessSessionProofs({ library })
        val forged = CONTEXT.copy(session = dispatchSession.copy(nativeId = "harness_owner_helper"))
        proofs.remember(forged)
        proofs.remember(forged.copy(owner = SessionOwner("another")))
        assertTrue(proofs.isResolved(forged.session))
        assertFalse(proofs.allows(harness.id, forged.session))
    }
}

private val PROJECT = WorkspaceRef("project")
private val CONTEXT = SessionHookContext(dispatchSession, WorkspaceRef("checkout"), null, null, SessionOwner("owner"))
