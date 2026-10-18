package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ScriptOwnershipResult
import io.aequicor.heartbeat.feature.harness.api.process.WaitReason
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.ProtectedImportedGitHub
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistry
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubReservation
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class OwnershipReturnTransferTest {
    private val repository = GitHubRepository("owner", "repo")
    private val process = ProcessId("return_transfer")

    @Test
    fun `returning owners receive newer counters and the complete permanent key inventory`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val first = OwnershipRuntimeTestEnv(this, server)
        val second = OwnershipRuntimeTestEnv(this, server)
        enroll(first)
        transfer(first, second)
        progress(second, 7)
        reserveRefusal(second, "second_host_key", 'b')
        val fromSecond = second.record(process)
        transfer(second, first)
        assertProgressPreserved(fromSecond, first.record(process))
        assertEquals(setOf(WriteKey("second_host_key")), importedKeys(first))

        progress(first, 11)
        reserveRefusal(first, "first_host_key", 'c')
        val fromFirst = first.record(process)
        transfer(first, second)
        assertProgressPreserved(fromFirst, second.record(process))
        assertEquals(setOf(WriteKey("second_host_key"), WriteKey("first_host_key")), importedKeys(second))
        assertEquals(4L, current(second).fence.epoch)
        assertTrue(second.record(process).grants.isEmpty())
    }

    @Test
    fun `published return import survives restart before activation without replacing the record again`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val first = OwnershipRuntimeTestEnv(this, server)
        val second = OwnershipRuntimeTestEnv(this, server)
        enroll(first)
        transfer(first, second)
        progress(second, 7)
        export(second, first)
        first.approve(first.ownership.claim(repository, GitHubTaskKind.Issue, 1, first.workspace))
        assertIs<ScriptOwnershipResult.Current>(
            first.ownership.claim(repository, GitHubTaskKind.Issue, 1, first.workspace),
        )
        var interruptedAfterPublish = false
        first.secrets.beforeRead = {
            val published = first.secrets.values.values.any { encoded ->
                val envelope = Json.parseToJsonElement(encoded) as? JsonObject
                val entries = envelope?.get("entries") as? JsonObject
                val stored = entries?.get(HarnessScriptProcesses.STORAGE_KEY)
                stored != null && HarnessScriptProcesses.decodeStored(stored).any {
                    it.id == process && it.inputRevision == 7L
                }
            }
            if (published) {
                interruptedAfterPublish = true
                server.hasAccess = false
            }
        }
        first.ownership.advance(process, first.workspace)
        first.secrets.beforeRead = {}
        server.hasAccess = true
        assertTrue(interruptedAfterPublish)
        val published = first.record(process)
        assertEquals(OwnershipPhase.Reconciling, current(second).phase)
        val restarted = OwnershipRuntimeTestEnv(this, server, first.secrets)
        assertEquals(
            OwnershipPhase.Active,
            assertIs<ScriptOwnershipResult.Current>(
                restarted.ownership.advance(process, restarted.workspace),
            ).ownership.phase,
        )
        assertEquals(published, restarted.record(process))
    }

    @Test
    fun `an unrelated local lifetime is not replaced by an approved external task`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val source = OwnershipRuntimeTestEnv(this, server)
        val target = OwnershipRuntimeTestEnv(this, server)
        enroll(source)
        val unrelated = target.processes.open(process, 99)
        assertNotEquals(source.record(process).incarnation, unrelated.incarnation)
        export(source, target)
        assertEquals(
            ScriptOwnershipResult.Refused,
            target.ownership.claim(repository, GitHubTaskKind.Issue, 1, target.workspace),
        )
        assertEquals(unrelated, target.record(process))
        assertEquals(OwnershipPhase.Exported, current(source).phase)
    }

    @Test
    fun `return import cannot roll back a retained owner counter even after claim`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val first = OwnershipRuntimeTestEnv(this, server)
        val second = OwnershipRuntimeTestEnv(this, server)
        enroll(first)
        transfer(first, second)
        progress(second, 7)
        val retained = first.record(process)
        // Model a retained local highwater ahead of the offered checkpoint using the trusted storage boundary.
        val ahead = retained.copy(nextWait = second.record(process).nextWait + 1)
        first.data.processMutex.withLock { assertTrue(first.local.replace(retained, ahead)) }
        export(second, first)
        val request = first.ownership.claim(repository, GitHubTaskKind.Issue, 1, first.workspace)
        if (request is ScriptOwnershipResult.Decision) {
            first.approve(request)
            first.ownership.claim(repository, GitHubTaskKind.Issue, 1, first.workspace)
            first.ownership.advance(process, first.workspace)
        }
        assertEquals(ahead, first.record(process))
        assertNotEquals(OwnershipPhase.Active, current(second).phase)
    }

    private suspend fun enroll(owner: OwnershipRuntimeTestEnv) {
        owner.processes.open(process, 1)
        owner.approve(owner.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, owner.workspace))
        assertIs<ScriptOwnershipResult.Current>(
            owner.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, owner.workspace),
        )
    }

    private suspend fun progress(owner: OwnershipRuntimeTestEnv, revision: Long) {
        owner.clock.seconds++
        assertIs<ProcessReduction.Changed>(owner.processes.apply(process, ProcessCommand.ReviseInput(revision)))
        assertIs<ProcessReduction.Changed>(
            owner.processes.apply(
                process,
                ProcessCommand.Wait(WaitReason.Timer, WakeCondition(deadline = Instant.fromEpochSeconds(10_000))),
            ),
        )
        val wait = owner.record(process).nextWait - 1
        assertIs<ProcessReduction.Changed>(owner.processes.apply(process, ProcessCommand.Resume(wait)))
    }

    private suspend fun reserveRefusal(owner: OwnershipRuntimeTestEnv, key: String, marker: Char) {
        val record = owner.record(process)
        val registry = GitHubRegistry(
            HarnessScriptData(owner.request.harness.id, owner.access, owner.data).processData(),
        )
        assertIs<GitHubReservation.Reserved>(
            registry.reserve(
                GitHubOperation(
                    process,
                    record.incarnation,
                    record.inputRevision,
                    WriteKey(key),
                    repository,
                    GitHubChange.Comment(1, "Never dispatched"),
                    marker = marker.toString().repeat(32),
                    refusal = GitHubWriteRefusal.NotAuthorized,
                    hasSendStarted = false,
                ),
            ),
        )
    }

    private suspend fun importedKeys(owner: OwnershipRuntimeTestEnv): Set<WriteKey> = ProtectedImportedGitHub(
        owner.secrets,
    ).snapshot(owner.record(process).local(owner.request.harness.id)).tombstones.map { it.key }.toSet()

    private fun assertProgressPreserved(expected: ProcessRecord, actual: ProcessRecord) {
        assertEquals(expected.incarnation, actual.incarnation)
        assertEquals(expected.createdAt, actual.createdAt)
        assertEquals(expected.inputRevision, actual.inputRevision)
        assertEquals(expected.nextAttempt, actual.nextAttempt)
        assertEquals(expected.nextWait, actual.nextWait)
        assertEquals(expected.nextWriteTry, actual.nextWriteTry)
    }

    private suspend fun transfer(source: OwnershipRuntimeTestEnv, target: OwnershipRuntimeTestEnv) {
        export(source, target)
        target.approve(target.ownership.claim(repository, GitHubTaskKind.Issue, 1, target.workspace))
        assertEquals(
            OwnershipPhase.Reconciling,
            assertIs<ScriptOwnershipResult.Current>(
                target.ownership.claim(repository, GitHubTaskKind.Issue, 1, target.workspace),
            ).ownership.phase,
        )
        assertEquals(
            OwnershipPhase.Active,
            assertIs<ScriptOwnershipResult.Current>(
                target.ownership.advance(process, target.workspace),
            ).ownership.phase,
        )
    }

    private suspend fun export(source: OwnershipRuntimeTestEnv, target: OwnershipRuntimeTestEnv) {
        val recipient = checkNotNull(target.ownership.host())
        source.approve(source.ownership.prepare(process, recipient, source.workspace))
        assertIs<ScriptOwnershipResult.Current>(source.ownership.prepare(process, recipient, source.workspace))
        assertIs<ScriptOwnershipResult.Current>(source.ownership.advance(process, source.workspace))
        assertEquals(
            OwnershipPhase.Exported,
            assertIs<ScriptOwnershipResult.Current>(
                source.ownership.advance(process, source.workspace),
            ).ownership.phase,
        )
    }

    private suspend fun current(owner: OwnershipRuntimeTestEnv) =
        assertIs<ScriptOwnershipResult.Current>(owner.ownership.status(process)).ownership
}
