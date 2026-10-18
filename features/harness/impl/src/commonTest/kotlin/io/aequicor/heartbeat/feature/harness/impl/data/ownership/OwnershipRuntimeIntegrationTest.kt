package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
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
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.NativeOwnershipAdmission
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnershipRuntimeIntegrationTest {
    private val repository = GitHubRepository("owner", "repo")
    private val process = ProcessId("transfer")

    @Test
    fun `native consent transfers idle process across independent profiles and fences old source`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val source = OwnershipRuntimeTestEnv(this, server)
        val target = OwnershipRuntimeTestEnv(this, server)
        source.processes.open(process, 7)
        val original = source.record(process)
        assertNotEquals(source.ownership.host(), target.ownership.host())
        assertNull(target.processes.get(process))

        val enrollment = source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace)
        assertIs<ScriptOwnershipResult.Decision>(enrollment)
        assertEquals(0, server.writes)
        assertNull(source.record(process).ownershipKey)
        source.approve(enrollment)
        val enrolled = assertIs<ScriptOwnershipResult.Current>(
            source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace),
        ).ownership
        assertEquals(OwnershipPhase.Active, enrolled.phase)
        assertEquals(enrolled.fence.key, source.record(process).ownershipKey)
        export(source, target)
        source.toggles.isEnabled = false

        target.approve(target.ownership.claim(repository, GitHubTaskKind.Issue, 1, target.workspace))
        assertEquals(
            OwnershipPhase.Reconciling,
            assertIs<ScriptOwnershipResult.Current>(
                target.ownership.claim(repository, GitHubTaskKind.Issue, 1, target.workspace),
            ).ownership.phase,
        )
        assertNull(target.processes.get(process))

        // The recipient restarts before import: only its protected binding/consent bytes survive.
        val restarted = OwnershipRuntimeTestEnv(this, server, target.secrets)
        val activated = assertIs<ScriptOwnershipResult.Current>(
            restarted.ownership.advance(process, restarted.workspace),
        ).ownership
        assertEquals(OwnershipPhase.Active, activated.phase)
        assertEquals(target.ownership.host(), activated.fence.host)
        assertEquals(enrolled.fence.epoch + 1, activated.fence.epoch)
        val imported = restarted.record(process)
        assertEquals(original.incarnation, imported.incarnation)
        assertEquals(original.inputRevision, imported.inputRevision)
        assertEquals(original.nextAttempt, imported.nextAttempt)
        assertEquals(original.nextWait, imported.nextWait)
        assertTrue(imported.grants.isEmpty())
        assertEquals(
            NativeOwnershipAdmission.Unavailable,
            source.runtime.enter(harness.id, source.record(process), OwnershipPermitKind.Command, "old-source"),
        )
        val admitted = assertIs<NativeOwnershipAdmission.Fresh>(
            restarted.runtime.enter(harness.id, imported, OwnershipPermitKind.Command, "new-target"),
        )
        assertTrue(restarted.runtime.complete(admitted.intent))
    }

    @Test
    fun `lost claim response resumes from protected recipient binding without second epoch rotation`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val source = OwnershipRuntimeTestEnv(this, server)
        val target = OwnershipRuntimeTestEnv(this, server)
        source.processes.open(process, 1)
        source.approve(source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace))
        source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace)
        export(source, target)
        val consent = assertIs<ScriptOwnershipResult.Decision>(
            target.ownership.claim(repository, GitHubTaskKind.Issue, 1, target.workspace),
        )
        target.approve(consent)
        server.loseNextResponse = true
        assertEquals(
            ScriptOwnershipResult.Unavailable,
            target.ownership.claim(repository, GitHubTaskKind.Issue, 1, target.workspace),
        )
        val claimed = assertIs<ScriptOwnershipResult.Current>(source.ownership.status(process)).ownership
        assertEquals(OwnershipPhase.Reconciling, claimed.phase)
        val restarted = OwnershipRuntimeTestEnv(this, server, target.secrets)
        val active = assertIs<ScriptOwnershipResult.Current>(
            restarted.ownership.claim(repository, GitHubTaskKind.Issue, 1, restarted.workspace),
        ).ownership
        assertEquals(OwnershipPhase.Active, active.phase)
        assertEquals(claimed.fence, active.fence)
        assertEquals(ProcessDecisionPhase.Completed, restarted.decisions.ready()?.find(consent.id)?.decision?.phase)
        assertEquals(
            ScriptOwnershipResult.Refused,
            source.ownership.advance(process, source.workspace),
        )
    }

    @Test
    fun `idle record cannot export while an admitted command has no positive native receipt`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val source = OwnershipRuntimeTestEnv(this, server)
        val target = OwnershipRuntimeTestEnv(this, server)
        source.processes.open(process, 1)
        source.approve(source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace))
        source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace)
        val admitted = assertIs<NativeOwnershipAdmission.Fresh>(
            source.runtime.enter(harness.id, source.record(process), OwnershipPermitKind.Command, "unknown-command"),
        )
        val recipient = checkNotNull(target.ownership.host())
        source.approve(source.ownership.prepare(process, recipient, source.workspace))
        source.ownership.prepare(process, recipient, source.workspace)
        source.ownership.advance(process, source.workspace)
        assertEquals(ScriptOwnershipResult.Pending, source.ownership.advance(process, source.workspace))
        // Restart and elapsed time cannot turn missing completion into a receipt.
        val restarted = OwnershipRuntimeTestEnv(this, server, source.secrets)
        restarted.clock.seconds += 100_000
        assertEquals(ScriptOwnershipResult.Pending, restarted.ownership.advance(process, restarted.workspace))
        val snapshot = assertIs<ScriptOwnershipResult.Current>(restarted.ownership.status(process)).ownership
        assertEquals(OwnershipPhase.Quiescing, snapshot.phase)
        assertEquals(listOf(admitted.intent.permit), snapshot.permits)
        assertEquals(
            ScriptOwnershipResult.Unavailable,
            target.ownership.claim(repository, GitHubTaskKind.Issue, 1, target.workspace),
        )
        assertNull(target.processes.get(process))
    }

    @Test
    fun `writer barrier settles unstarted native reservation before export`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val source = OwnershipRuntimeTestEnv(this, server)
        val target = OwnershipRuntimeTestEnv(this, server)
        source.processes.open(process, 1)
        source.approve(source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace))
        source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace)
        val record = source.record(process)
        val reservation = ProcessWorkflow(RunId("wf_unstarted"), code.id, "a".repeat(64))
        assertIs<ProcessReduction.Changed>(
            source.processes.applyHost(
                process,
                ProcessCommand.StartWorkflow(
                    record.workflowKey(StageName("work")),
                    reservation,
                    record.inputRevision,
                    record.subject,
                    record.definition,
                ),
            ),
        )
        assertIs<NativeOwnershipAdmission.Fresh>(
            source.runtime.enter(
                harness.id,
                source.record(process),
                OwnershipPermitKind.Workflow,
                reservation.run.value,
            ),
        )
        export(source, target)
        val closed = source.record(process)
        assertNull(closed.inFlight)
        assertTrue(checkNotNull(closed.history.single().attempt.workflow).isSettled)
        assertTrue(
            assertIs<ScriptOwnershipResult.Current>(source.ownership.status(process)).ownership.permits.isEmpty(),
        )
    }

    private suspend fun export(source: OwnershipRuntimeTestEnv, target: OwnershipRuntimeTestEnv) {
        val recipient = checkNotNull(target.ownership.host())
        source.approve(source.ownership.prepare(process, recipient, source.workspace))
        assertEquals(
            OwnershipPhase.Preparing,
            assertIs<ScriptOwnershipResult.Current>(
                source.ownership.prepare(process, recipient, source.workspace),
            ).ownership.phase,
        )
        assertEquals(
            OwnershipPhase.Quiescing,
            assertIs<ScriptOwnershipResult.Current>(
                source.ownership.advance(process, source.workspace),
            ).ownership.phase,
        )
        assertEquals(
            OwnershipPhase.Exported,
            assertIs<ScriptOwnershipResult.Current>(
                source.ownership.advance(process, source.workspace),
            ).ownership.phase,
        )
    }
}
