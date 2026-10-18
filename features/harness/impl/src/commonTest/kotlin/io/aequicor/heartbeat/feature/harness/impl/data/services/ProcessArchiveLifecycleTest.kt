package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveReason
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessEnd
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.WaitReason
import io.aequicor.heartbeat.feature.harness.api.process.waitKey
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProcessArchiveLifecycleTest {
    private val owner = HarnessId("owner")
    private val id = ProcessId("task")
    private val definition = ProcessDefinition(ItemId("script"), 1)
    private val condition = WakeCondition(events = setOf(EventKeys.custom("process.answer")))

    @Test
    fun `removal confirms a protected snapshot before releasing the active slot`() = runTest {
        val env = ProcessesTestEnv(this, definition = definition, isArchiveEnabled = true)
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Close(ProcessEnd.Completed))
        val original = requireNotNull(env.processes.get(id))
        val snapshot = requireNotNull(env.processes.archive(id))
        assertEquals(original, env.processes.get(id))
        assertTrue(env.processes.remove(id))
        assertNull(env.processes.get(id))
        assertEquals(original, env.processes.readArchive(snapshot.ref)?.record)
        assertEquals(1, env.processes.archived().entries.size)
    }

    @Test
    fun `failed or unconfirmed archival keeps the original active record`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val env = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Close(ProcessEnd.Completed))
        val original = env.processes.get(id)
        secrets.beforeWrite = { error("archive backend failed") }
        assertFailsWith<IllegalStateException> { env.processes.remove(id) }
        secrets.beforeWrite = {}
        assertEquals(original, env.processes.get(id))
        assertTrue(env.archives.list().entries.isEmpty())

        val silent = ProcessesTestEnv(
            this,
            DroppedArchiveWrites(secrets),
            definition = definition,
            isArchiveEnabled = true,
        )
        assertFailsWith<IllegalStateException> { silent.processes.remove(id) }
        assertEquals(original, silent.processes.get(id))
        assertTrue(silent.archives.list().entries.isEmpty())
    }

    @Test
    fun `a crash after archival before active commit is retried without duplicate snapshots`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val env = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Close(ProcessEnd.Completed))
        val original = env.processes.get(id)
        var writes = 0
        secrets.beforeWrite = {
            writes++
            if (writes == 2) error("active commit interrupted")
        }
        assertFailsWith<IllegalStateException> { env.processes.remove(id) }
        secrets.beforeWrite = {}
        assertEquals(original, env.processes.get(id))
        assertEquals(1, env.archives.list().entries.size)
        assertTrue(env.processes.remove(id))
        assertNull(env.processes.get(id))
        assertEquals(1, env.archives.list().entries.size)
    }

    @Test
    fun `completed backlog leaves the active quota only after archival`() = runTest {
        val env = ProcessesTestEnv(this, definition = definition, isArchiveEnabled = true)
        repeat(ProcessLimits.PROCESSES) { index ->
            val process = ProcessId("task_$index")
            env.processes.open(process)
            env.processes.apply(process, ProcessCommand.Close(ProcessEnd.Completed))
        }
        env.processes.open(id)
        assertEquals(ProcessLimits.PROCESSES, env.processes.list().size)
        val archived = env.archives.list().entries.single()
        assertEquals(ProcessId("task_0"), archived.record.id)
        assertEquals(ProcessArchiveReason.Completed, archived.reason)
        assertNull(env.processes.get(archived.record.id))
    }

    @Test
    fun `disabled archive reclamation preserves legacy records in a mixed namespace`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val legacy = ProcessesTestEnv(this, secrets, isVersioningEnabled = { false })
        val legacyId = ProcessId("legacy")
        legacy.processes.open(legacyId)
        legacy.processes.apply(legacyId, ProcessCommand.Close(ProcessEnd.Completed))
        val pinned = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        repeat(ProcessLimits.PROCESSES - 1) { index ->
            val process = ProcessId("pinned_$index")
            pinned.processes.open(process)
            pinned.processes.apply(process, ProcessCommand.Close(ProcessEnd.Completed))
        }
        val disabled = ProcessesTestEnv(
            this,
            secrets,
            definition = definition,
            isVersioningEnabled = { false },
            isArchiveEnabled = false,
        )
        disabled.processes.open(id)
        assertEquals(legacy.processes.get(legacyId), disabled.processes.get(legacyId))
        assertEquals(ProcessId("pinned_0"), disabled.archives.list().entries.single().record.id)
    }

    @Test
    fun `turning the flag off cannot delete a previously pinned process without archival`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val enabled = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        enabled.processes.open(id)
        enabled.processes.apply(id, ProcessCommand.Close(ProcessEnd.Completed))
        val disabled = ProcessesTestEnv(
            this,
            secrets,
            definition = definition,
            isVersioningEnabled = { false },
            isArchiveEnabled = false,
        )
        assertTrue(disabled.processes.remove(id))
        assertEquals(id, disabled.archives.list().entries.single().record.id)
    }

    @Test
    fun `harness deletion preserves waits and stable workflow keys across restoration`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val before = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        before.processes.open(id)
        before.processes.apply(id, ProcessCommand.Wait(WaitReason.External, condition))
        val original = requireNotNull(before.processes.get(id))
        val originalKey = original.workflowKey(StageName("verify"))
        before.access.isActive = false
        before.storage.prepareRemoval(owner)
        before.storage.retainOwners(emptySet())
        val snapshot = before.archives.list().entries.single()
        assertEquals(ProcessArchiveReason.HarnessDeleted, snapshot.reason)
        assertEquals(original, snapshot.record)
        assertFailsWith<IllegalStateException> { before.processes.readArchive(snapshot.ref) }

        val after = ProcessesTestEnv(
            this,
            secrets,
            restartedAt = 7 * 24 * 3600,
            definition = definition,
            isArchiveEnabled = true,
        )
        val restored = requireNotNull(after.processes.restore(snapshot.ref))
        assertEquals(originalKey, restored.workflowKey(StageName("verify")))
        assertEquals(original, restored)
        assertNull(restored.inFlight)
        val key = requireNotNull(restored.waitKey())
        val resumed = assertIs<ProcessReduction.Changed>(
            after.processes.apply(id, ProcessCommand.ResumeChecked(key)),
        ).record
        assertEquals(resumed, after.processes.restore(snapshot.ref)) // No rewind on an idempotent retry.
        after.processes.apply(id, ProcessCommand.Wait(WaitReason.Human, condition))
        assertIs<ProcessReduction.Unchanged>(after.processes.apply(id, ProcessCommand.ResumeChecked(key)))
        assertEquals(2, assertIs<ProcessPhase.Waiting>(requireNotNull(after.processes.get(id)).phase).wait.number)
    }

    @Test
    fun `restore cannot overwrite another lifetime and other harness archives stay inaccessible`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val env = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Close(ProcessEnd.Completed))
        val snapshot = requireNotNull(env.processes.archive(id))
        env.processes.remove(id)
        val replacement = env.processes.open(id)
        assertFailsWith<IllegalStateException> { env.processes.restore(snapshot.ref) }
        assertEquals(replacement, env.processes.get(id))
        val other = ProcessesTestEnv(this, secrets, owner = HarnessId("other"), isArchiveEnabled = true)
        assertNull(other.processes.restore(snapshot.ref))
        assertNull(other.processes.readArchive(snapshot.ref))
        assertEquals(snapshot, other.archives.get(snapshot.ref)) // Profile reader still resolves the durable link.
    }

    @Test
    fun `archive reads refuse results when activation retires during protected IO`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val env = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Close(ProcessEnd.Completed))
        val snapshot = requireNotNull(env.processes.archive(id))
        for (isListing in listOf(false, true)) {
            env.access.isActive = true
            var reads = 0
            secrets.beforeRead = {
                if (++reads == 2) env.access.isActive = false
            }
            assertFailsWith<IllegalStateException> {
                if (isListing) env.processes.archived() else env.processes.readArchive(snapshot.ref)
            }
            secrets.beforeRead = {}
            assertEquals(snapshot, env.archives.get(snapshot.ref))
        }
    }

    @Test
    fun `cancelled archive reads cannot deliver protected results`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val env = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Close(ProcessEnd.Completed))
        val snapshot = requireNotNull(env.processes.archive(id))
        for (isListing in listOf(false, true)) {
            var reads = 0
            secrets.beforeRead = {
                if (++reads == 2) currentCoroutineContext().cancel()
            }
            var isReturned = false
            val reading = async {
                if (isListing) env.processes.archived() else env.processes.readArchive(snapshot.ref)
                isReturned = true
            }
            assertFailsWith<CancellationException> { reading.await() }
            assertFalse(isReturned)
            secrets.beforeRead = {}
            assertEquals(snapshot, env.archives.get(snapshot.ref))
        }
    }

    @Test
    fun `full archive keeps active records and prevents destructive harness cleanup`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val env = ProcessesTestEnv(this, secrets, definition = definition, isArchiveEnabled = true)
        val at = Instant.fromEpochSeconds(PROCESS_TEST_START)
        repeat(ProtectedProcessArchives.CAPACITY) { index ->
            env.archives.store(
                owner,
                ProcessRecord(ProcessId("old_$index"), at, phase = ProcessPhase.Ended(ProcessEnd.Completed, at)),
                ProcessArchiveReason.Completed,
            )
        }
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Close(ProcessEnd.Completed))
        val original = env.processes.get(id)
        assertFailsWith<IllegalStateException> { env.processes.remove(id) }
        assertEquals(original, env.processes.get(id))
        assertFailsWith<IllegalStateException> { env.storage.prepareRemoval(owner) }
        assertEquals(original, env.processes.get(id))
        assertFailsWith<IllegalStateException> { env.storage.retainOwners(emptySet()) }
        assertEquals(original, env.processes.get(id))
    }
}

/** A backend that acknowledges archive writes without persistence must never authorize active deletion. */
private class DroppedArchiveWrites(private val delegate: SecretStore) : SecretStore by delegate {
    override suspend fun write(key: SecretKey, value: Secret) {
        if (!key.value.startsWith("harness_pa_")) delegate.write(key, value)
    }
}
