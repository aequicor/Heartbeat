package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ScriptOwnershipResult
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OwnershipStaleDrainTest {
    @Test
    fun `old drain cannot rewrite subject after cancellation activates a newer epoch`() = runTest {
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
        writer.locksUntilPause = 2
        val old = async { source.ownership.advance(process, source.workspace) }
        writer.paused.await()
        source.approve(source.ownership.cancel(process, source.workspace))
        source.ownership.cancel(process, source.workspace)
        val active = assertIs<ScriptOwnershipResult.Current>(source.ownership.advance(process, source.workspace))
        assertEquals(OwnershipPhase.Active, active.ownership.phase)
        source.processes.apply(process, ProcessCommand.ObserveSubject(SubjectSha("b".repeat(40))))
        val fresh = source.record(process)
        source.commands.head = "c".repeat(40)
        writer.resume.complete(Unit)
        assertEquals(ScriptOwnershipResult.Pending, old.await())
        assertEquals(fresh, source.record(process))
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
