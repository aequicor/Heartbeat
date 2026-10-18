package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflow
import io.aequicor.heartbeat.feature.harness.api.process.ScriptOwnershipResult
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.NativeOwnershipAdmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnershipSourceReconcileRaceTest {
    @Test
    fun `restart activates a source whose cleanup receipt was saved before the remote write failed`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val source = OwnershipRuntimeTestEnv(this, server)
        val target = OwnershipRuntimeTestEnv(this, server)
        val process = ProcessId("source_reconciliation_restart")
        val repository = GitHubRepository("owner", "repo")
        source.processes.open(process, 1)
        source.approve(source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace))
        source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace)
        val recipient = checkNotNull(target.ownership.host())
        source.approve(source.ownership.prepare(process, recipient, source.workspace))
        source.ownership.prepare(process, recipient, source.workspace)
        source.approve(source.ownership.cancel(process, source.workspace))
        source.ownership.cancel(process, source.workspace)
        var writes = 0
        source.secrets.beforeWrite = {
            if (++writes == 2) server.hasAccess = false
        }
        source.ownership.advance(process, source.workspace)
        assertTrue(source.runtime.journal.bindings().single().isSourceReconciled)
        source.secrets.beforeWrite = {}
        server.hasAccess = true
        val restarted = OwnershipRuntimeTestEnv(this, server, source.secrets, source.request)
        val active = assertIs<ScriptOwnershipResult.Current>(restarted.ownership.advance(process, restarted.workspace))
        assertEquals(OwnershipPhase.Active, active.ownership.phase)
        assertEquals(source.record(process), restarted.record(process))
    }

    @Test
    fun `stale source reconciliation cannot close work admitted after activation of the same epoch`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val writer = ReconciliationPausingWriter()
        val source = OwnershipRuntimeTestEnv(this, server, writer = writer)
        val target = OwnershipRuntimeTestEnv(this, server)
        val process = ProcessId("source_reconciliation_race")
        val repository = GitHubRepository("owner", "repo")
        source.processes.open(process, 1)
        source.approve(source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace))
        source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace)
        val recipient = checkNotNull(target.ownership.host())
        source.approve(source.ownership.prepare(process, recipient, source.workspace))
        source.ownership.prepare(process, recipient, source.workspace)
        source.approve(source.ownership.cancel(process, source.workspace))
        val reconciling = assertIs<ScriptOwnershipResult.Current>(
            source.ownership.cancel(process, source.workspace),
        ).ownership
        assertEquals(OwnershipPhase.Reconciling, reconciling.phase)
        val reconcilingFile = server.files.values.single()

        writer.locksUntilPause = 1
        val old = async { source.ownership.advance(process, source.workspace) }
        writer.paused.await()
        val activated = assertIs<ScriptOwnershipResult.Current>(
            source.ownership.advance(process, source.workspace),
        ).ownership
        assertEquals(OwnershipPhase.Active, activated.phase)
        assertEquals(reconciling.fence, activated.fence)

        val before = source.record(process)
        val reservation = ProcessWorkflow(RunId("wf_after_source_activation"), source.request.item.id, "a".repeat(64))
        assertIs<ProcessReduction.Changed>(
            source.processes.applyHost(
                process,
                ProcessCommand.StartWorkflow(
                    before.workflowKey(StageName("work")),
                    reservation,
                    before.inputRevision,
                    before.subject,
                    before.definition,
                ),
            ),
        )
        assertIs<NativeOwnershipAdmission.Fresh>(
            source.runtime.enter(
                source.request.harness.id,
                source.record(process),
                OwnershipPermitKind.Workflow,
                reservation.run.value,
            ),
        )
        val fresh = source.record(process)
        server.staleRead = reconcilingFile
        writer.resume.complete(Unit)
        old.await()
        assertEquals(fresh, source.record(process))
    }
}

/** Stops the first reconciler before its writer lock, allowing another caller to activate the same fence. */
private class ReconciliationPausingWriter(private val delegate: Mutex = Mutex()) : Mutex by delegate {
    var locksUntilPause = -1
    val paused = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()

    override suspend fun lock(owner: Any?) {
        if (--locksUntilPause == 0) {
            paused.complete(Unit)
            resume.await()
        }
        delegate.lock(owner)
    }
}
