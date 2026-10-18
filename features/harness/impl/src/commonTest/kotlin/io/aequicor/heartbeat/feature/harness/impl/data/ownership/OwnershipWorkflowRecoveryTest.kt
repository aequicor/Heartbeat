package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflow
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.ScriptOwnershipResult
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowProcessOwner
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.NativeOwnershipAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowFixture
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class OwnershipWorkflowRecoveryTest {
    private val repository = GitHubRepository("owner", "repo")
    private val process = ProcessId("workflow_recovery")
    private val digest = "a".repeat(64)

    @Test
    fun `cancel restores source admission without converting an opaque completed workflow result`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val source = enrolled(server)
        val target = OwnershipRuntimeTestEnv(this, server)
        val before = source.record(process)
        val key = before.workflowKey(StageName("work"))
        val reservation = ProcessWorkflow(RunId("wf_opaque"), source.request.item.id, digest)
        assertIs<ProcessReduction.Changed>(
            source.processes.applyHost(
                process,
                ProcessCommand.StartWorkflow(key, reservation, before.inputRevision, before.subject, before.definition),
            ),
        )
        val admission = assertIs<NativeOwnershipAdmission.Fresh>(
            source.runtime.enter(
                source.request.harness.id,
                source.record(process),
                OwnershipPermitKind.Workflow,
                reservation.run.value,
            ),
        )
        val opaque = buildJsonObject { put("answer", "Original local workflow result") }
        val run = WorkflowFixture().initial.copy(
            id = reservation.run,
            harness = source.request.harness.id,
            startedAt = source.clock.now(),
            deadline = source.clock.now() + 1.hours,
            status = WorkflowStatus.Completed(opaque),
            finishedAt = source.clock.now(),
            process = WorkflowProcessOwner(source.request.item.id, key, digest, admission.intent.permit),
        )
        assertTrue(source.runs.save(run, null))
        val attempt = assertNotNull(source.record(process).inFlight)
        prepare(source, target)
        source.approve(source.ownership.cancel(process, source.workspace))
        assertEquals(
            OwnershipPhase.Reconciling,
            assertIs<ScriptOwnershipResult.Current>(source.ownership.cancel(process, source.workspace)).ownership.phase,
        )

        val resumed = assertIs<ScriptOwnershipResult.Current>(source.ownership.advance(process, source.workspace))
        assertEquals(OwnershipPhase.Active, resumed.ownership.phase)
        assertTrue(resumed.ownership.permits.isEmpty())
        assertEquals(attempt, source.record(process).inFlight)
        assertEquals(WorkflowStatus.Completed(opaque), source.runs.find(run.id)?.status)
        assertIs<ProcessReduction.Changed>(
            source.processes.apply(
                process,
                ProcessCommand.Finish(
                    attempt.number,
                    AttemptOutcome.Succeeded(ResultRef("workflow_result", run.id.value)),
                ),
            ),
        )
        assertNull(source.record(process).inFlight)
        assertEquals(WorkflowStatus.Completed(opaque), source.runs.find(run.id)?.status)
    }

    @Test
    fun `restart settles an unstarted reservation already closed in process history`() = runTest {
        recoverClosedReservation(isSettled = false)
    }

    @Test
    fun `restart releases an unstarted permit after its local cleanup receipt was committed`() = runTest {
        recoverClosedReservation(isSettled = true)
    }

    private suspend fun TestScope.recoverClosedReservation(isSettled: Boolean) {
        val server = GitHubOwnershipBackendTest.Server()
        val source = enrolled(server)
        val target = OwnershipRuntimeTestEnv(this, server)
        val before = source.record(process)
        val key = before.workflowKey(StageName("work"))
        val reservation = ProcessWorkflow(RunId("wf_unstarted_recovery"), source.request.item.id, digest)
        assertIs<ProcessReduction.Changed>(
            source.processes.applyHost(
                process,
                ProcessCommand.StartWorkflow(key, reservation, before.inputRevision, before.subject, before.definition),
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
        prepare(source, target)
        assertEquals(
            OwnershipPhase.Quiescing,
            assertIs<ScriptOwnershipResult.Current>(
                source.ownership.advance(process, source.workspace),
            ).ownership.phase,
        )
        assertIs<ProcessReduction.Changed>(
            source.processes.applyHost(process, ProcessCommand.CancelWorkflow(key, reservation)),
        )
        if (isSettled) {
            assertIs<ProcessReduction.Changed>(
                source.processes.applyHost(process, ProcessCommand.SettleWorkflow(key, reservation)),
            )
        }
        assertNull(source.record(process).inFlight)
        assertNull(source.runs.find(reservation.run))
        val pending = assertIs<ScriptOwnershipResult.Current>(source.ownership.status(process)).ownership
        assertEquals(1, pending.permits.size)

        // The process journal and protected native proposal survive; no native run was ever dispatched.
        val restarted = OwnershipRuntimeTestEnv(this, server, source.secrets)
        val exported = assertIs<ScriptOwnershipResult.Current>(
            restarted.ownership.advance(process, restarted.workspace),
        ).ownership
        assertEquals(OwnershipPhase.Exported, exported.phase)
        assertTrue(exported.permits.isEmpty())
        assertTrue(checkNotNull(restarted.record(process).history.single().attempt.workflow).isSettled)
        assertNull(restarted.runs.find(reservation.run))
    }

    private suspend fun TestScope.enrolled(server: GitHubOwnershipBackendTest.Server): OwnershipRuntimeTestEnv {
        val source = OwnershipRuntimeTestEnv(this, server)
        source.processes.open(process, 1)
        source.approve(source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace))
        assertIs<ScriptOwnershipResult.Current>(
            source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace),
        )
        return source
    }

    private suspend fun prepare(source: OwnershipRuntimeTestEnv, target: OwnershipRuntimeTestEnv) {
        val recipient = checkNotNull(target.ownership.host())
        source.approve(source.ownership.prepare(process, recipient, source.workspace))
        assertEquals(
            OwnershipPhase.Preparing,
            assertIs<ScriptOwnershipResult.Current>(
                source.ownership.prepare(process, recipient, source.workspace),
            ).ownership.phase,
        )
    }
}
