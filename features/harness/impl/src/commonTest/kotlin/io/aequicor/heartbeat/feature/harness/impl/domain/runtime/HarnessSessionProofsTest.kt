package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessSessionProofsTest {
    @Test
    fun `worktree resolves source and every lookup reads current project proof`() {
        val harness = runtimeRequest(1).harness.copy(scope = HarnessScope.Projects(setOf(PROJECT)))
        val library = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
        var worktrees: WorktreeState = WorktreeState.Loading
        var projects: Set<WorkspaceRef>? = null
        val proofs = HarnessSessionProofs({ library }, { worktrees }, { projects })
        proofs.remember(CONTEXT)
        assertFalse(proofs.isResolved(dispatchSession))
        worktrees = WorktreeState.Ready(mapOf("chat" to WorktreeTask("chat", PROJECT, executionWorkspace = CHECKOUT)))
        assertTrue(proofs.allows(harness.id, dispatchSession))
        worktrees = WorktreeState.Loading
        assertFalse(proofs.allows(harness.id, dispatchSession))
        projects = setOf(PROJECT)
        assertFalse(proofs.isResolved(dispatchSession))
        val ordinary = dispatchSession.copy(nativeId = "ordinary")
        proofs.remember(CONTEXT.copy(session = ordinary, workspace = PROJECT))
        assertFalse(proofs.allows(harness.id, ordinary))
        worktrees = WorktreeState.Ready()
        assertTrue(proofs.allows(harness.id, ordinary))
    }

    @Test
    fun `catalog promoted checkout remains unknown until durable source mapping is restored`() {
        val original = runtimeRequest(1).harness.copy(scope = HarnessScope.Projects(setOf(PROJECT)))
        val checkout = original.copy(
            id = HarnessId("checkout"),
            name = HarnessName("checkout"),
            scope = HarnessScope.Projects(setOf(CHECKOUT)),
        )
        val library = HarnessState.Ready(
            listOf(HarnessEntry(original), HarnessEntry(checkout)),
            isRuntimeAvailable = true,
        )
        var worktrees: WorktreeState? = null
        val proofs = HarnessSessionProofs({ library }, { worktrees }, { setOf(CHECKOUT) })
        proofs.remember(CONTEXT)
        listOf(null, WorktreeState.Idle, WorktreeState.Loading, WorktreeState.LoadError).forEach {
            worktrees = it
            assertFalse(proofs.isResolved(dispatchSession))
            assertFalse(proofs.allows(original.id, dispatchSession))
            assertFalse(proofs.allows(checkout.id, dispatchSession))
        }
        worktrees = WorktreeState.Ready(mapOf("chat" to WorktreeTask("chat", PROJECT, executionWorkspace = CHECKOUT)))
        assertTrue(proofs.isResolved(dispatchSession))
        assertTrue(proofs.allows(original.id, dispatchSession))
        assertFalse(proofs.allows(checkout.id, dispatchSession))
    }

    @Test
    fun `attachments pending removal and suspension change admission immediately`() {
        val harness = runtimeRequest(1).harness.copy(scope = HarnessScope.Attached)
        var library = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
        val proofs = HarnessSessionProofs({ library }, { null }, { null })
        proofs.remember(CONTEXT)
        assertFalse(proofs.allows(harness.id, dispatchSession))
        library = library.copy(attachments = mapOf(dispatchSession to setOf(harness.id)))
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
    fun `unknown project cannot bypass deterministic cap by hiding possible project harnesses`() {
        val base = runtimeRequest(1).harness
        val profile = (1..8).map { index ->
            base.copy(id = HarnessId("profile$index"), name = HarnessName("profile$index"))
        }
        val project = base.copy(
            id = HarnessId("aaa"),
            name = HarnessName("aaa"),
            scope = HarnessScope.Projects(setOf(PROJECT)),
        )
        val library = HarnessState.Ready((profile + project).map(::HarnessEntry), isRuntimeAvailable = true)
        var projects: Set<WorkspaceRef>? = null
        val proofs = HarnessSessionProofs({ library }, { WorktreeState.Ready() }, { projects })
        proofs.remember(CONTEXT.copy(workspace = PROJECT))
        assertFalse(proofs.isResolved(dispatchSession))
        assertFalse(proofs.allows(profile.last().id, dispatchSession))
        projects = setOf(PROJECT)
        assertTrue(proofs.isResolved(dispatchSession))
        assertTrue(proofs.allows(project.id, dispatchSession))
        assertFalse(proofs.allows(profile.last().id, dispatchSession))
    }

    @Test
    fun `profile scope needs trusted session but not unrelated project IO`() {
        val harness = runtimeRequest(1).harness
        val library = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
        val proofs = HarnessSessionProofs({ library }, { null }, { null })
        assertFalse(proofs.allows(harness.id, dispatchSession))
        proofs.remember(CONTEXT)
        assertTrue(proofs.isResolved(dispatchSession))
        assertTrue(proofs.allows(harness.id, dispatchSession))
        proofs.remember(CONTEXT.copy(workspace = PROJECT))
        assertFalse(proofs.isResolved(dispatchSession))
        proofs.remember(CONTEXT)
        assertFalse(proofs.allows(harness.id, dispatchSession))
    }

    @Test
    fun `session naming and extra owner never grant helper affiliation`() {
        val harness = runtimeRequest(1).harness.copy(scope = HarnessScope.Attached)
        val library = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
        val proofs = HarnessSessionProofs({ library }, { null }, { null })
        val forged = CONTEXT.copy(session = dispatchSession.copy(nativeId = "harness_owner_helper"), workspace = null)
        proofs.remember(forged)
        proofs.remember(forged.copy(owner = SessionOwner("another")))
        assertTrue(proofs.isResolved(forged.session))
        assertFalse(proofs.allows(harness.id, forged.session))
    }
}

private val PROJECT = WorkspaceRef("project")
private val CHECKOUT = WorkspaceRef("checkout")
private val CONTEXT = SessionHookContext(dispatchSession, CHECKOUT, null, null, SessionOwner("owner"))
