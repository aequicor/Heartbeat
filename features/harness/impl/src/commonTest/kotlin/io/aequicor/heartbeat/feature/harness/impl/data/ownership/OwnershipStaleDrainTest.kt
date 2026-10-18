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
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
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

class OwnershipStaleDrainTest {
    @Test
    fun `old drain cannot rewrite subject after cancellation activates a newer epoch`() = verifyDrain(false)

    @Test
    fun `stale GET cannot let old drain rewrite the active generation`() = verifyDrain(true)

    @Test
    fun `stale GET cannot settle a workflow reserved by the active generation`() =
        verifyDrain(isStaleRead = true, isBeforeDrain = true)

    private fun verifyDrain(isStaleRead: Boolean, isBeforeDrain: Boolean = false) = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val writer = PausingWriter()
        val source = OwnershipRuntimeTestEnv(this, server, writer = writer)
        val target = OwnershipRuntimeTestEnv(this, server)
        val process = ProcessId("stale_drain")
        val repository = GitHubRepository("owner", "repo")
        source.processes.open(process, 1)
        source.approve(source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace))
        source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace)
        val recipient = checkNotNull(target.ownership.host())
        source.approve(source.ownership.prepare(process, recipient, source.workspace))
        source.ownership.prepare(process, recipient, source.workspace)
        source.ownership.advance(process, source.workspace)
        val quiescingFile = server.files.values.single()
        writer.locksUntilPause = if (isBeforeDrain) 1 else 2
        val old = async { source.ownership.advance(process, source.workspace) }
        writer.paused.await()
        source.approve(source.ownership.cancel(process, source.workspace))
        source.ownership.cancel(process, source.workspace)
        val active = assertIs<ScriptOwnershipResult.Current>(source.ownership.advance(process, source.workspace))
        assertEquals(OwnershipPhase.Active, active.ownership.phase)
        source.processes.apply(process, ProcessCommand.ObserveSubject(SubjectSha("b".repeat(40))))
        if (isBeforeDrain) {
            val before = source.record(process)
            val reservation = ProcessWorkflow(RunId("wf_new_generation"), source.request.item.id, "a".repeat(64))
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
        }
        val fresh = source.record(process)
        source.commands.head = "c".repeat(40)
        if (isStaleRead) server.staleRead = quiescingFile
        writer.resume.complete(Unit)
        val outcome = old.await()
        assertEquals(fresh.subject, source.record(process).subject)
        assertEquals(fresh, source.record(process))
        assertEquals(ScriptOwnershipResult.Pending, outcome)
    }
}

/** Pauses before acquiring the second writer barrier, leaving cancellation free to make progress. */
private class PausingWriter(private val delegate: Mutex = Mutex()) : Mutex by delegate {
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
