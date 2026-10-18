package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflow
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowProcessOwner
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.GitHubOwnershipBackendTest
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.ProfileOwnershipRuntime
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.ProtectedOwnershipJournal
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.data.services.PROCESS_TEST_START
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestClock
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestEnv
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipChange
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipRead
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.NativeOwnershipAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.services.confirmProcessWorkflow
import kotlinx.coroutines.test.TestScope
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Actual protected parent permit plus the production terminal receipt bridge; only GitHub IO is substituted. */
internal class OwnedWorkflowTestEnv(scope: TestScope, private val initial: WorkflowRun, private val digest: String) {
    private val secrets = HarnessDataTestSecrets()
    private val clock = ProcessesTestClock(PROCESS_TEST_START)
    private val server = GitHubOwnershipBackendTest.Server()
    private val runtime = ProfileOwnershipRuntime(ProtectedOwnershipJournal(secrets), server, clock)
    private val env = ProcessesTestEnv(
        scope,
        secrets,
        harness.id,
        definition = ProcessDefinition(code.id, harness.revision),
        ownership = runtime,
    )
    private val process = ProcessId("process")
    private val reservation = ProcessWorkflow(initial.id, code.id, digest)
    private lateinit var identity: GitHubTaskIdentity
    private val key get() = OwnershipKey(identity.canonical.encodeUtf8().sha256().hex())

    suspend fun reserve(): WorkflowRun {
        val opened = env.processes.open(process, 1)
        identity = checkNotNull(runtime.task(GitHubRepository("owner", "repo"), GitHubTaskKind.Issue, 1))
        val fence = OwnershipFence(key, runtime.journal.host(), 1)
        val local = OwnershipLocalProcess(harness.id, opened.id, opened.incarnation)
        assertTrue(runtime.journal.bind(null, OwnershipLocalBinding(local, identity, fence)))
        assertIs<OwnershipChange.Current>(runtime.coordinator(identity).create(key))
        val record = opened.copy(ownershipKey = key)
        env.data.processData().update(HarnessScriptProcesses.STORAGE_KEY) {
            HarnessScriptProcesses.encodeStored(listOf(record))
        }
        val key = record.workflowKey(StageName("work"))
        assertIs<ProcessReduction.Changed>(
            env.processes.applyHost(
                process,
                ProcessCommand.StartWorkflow(key, reservation, record.inputRevision, record.subject, record.definition),
            ),
        )
        val admitted = assertIs<NativeOwnershipAdmission.Fresh>(
            runtime.enter(
                harness.id,
                checkNotNull(env.processes.get(process)),
                OwnershipPermitKind.Workflow,
                initial.id.value,
            ),
        )
        return initial.copy(process = WorkflowProcessOwner(code.id, key, digest, admitted.intent.permit))
    }

    suspend fun permits() = (runtime.coordinator(identity).read(key) as OwnershipRead.Current).record!!.permits

    suspend fun terminal(run: WorkflowRun) {
        // Mirrors the owning workflow bridge: close the exact cancelled attempt, then persist its native receipt.
        assertIs<ProcessReduction.Changed>(
            env.processes.applyHost(
                process,
                ProcessCommand.CancelWorkflow(checkNotNull(run.process).key, reservation),
            ),
        )
        env.storage.confirmProcessWorkflow(harness.id, run, clock.now())
        assertTrue(checkNotNull(checkNotNull(env.processes.get(process)).history.single().attempt.workflow).isSettled)
    }
}
