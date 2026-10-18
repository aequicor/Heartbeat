package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveReason
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveRef
import io.aequicor.heartbeat.feature.harness.api.process.ProcessEnd
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProtectedProcessArchivesTest {
    private val owner = HarnessId("owner")
    private val at = Instant.fromEpochSeconds(1000)

    private fun ended(id: String = "task"): ProcessRecord = ProcessRecord(
        ProcessId(id),
        at,
        phase = ProcessPhase.Ended(ProcessEnd.Completed, at),
    )

    @Test
    fun `an immutable reference survives restart and an exact archival retry`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProtectedProcessArchives(secrets, ProcessesTestClock(1000), CommandToggles())
        val snapshot = first.store(owner, ended(), ProcessArchiveReason.Completed)
        val restarted = ProtectedProcessArchives(secrets, ProcessesTestClock(9000), CommandToggles())
        assertEquals(snapshot, restarted.get(snapshot.ref))
        assertEquals(snapshot, restarted.store(owner, ended(), ProcessArchiveReason.Completed))
        assertEquals(1, secrets.values.size)
        assertTrue(secrets.values.keys.all { it.value.length <= 128 })
        assertNull(restarted.getOwned(HarnessId("other"), snapshot.ref))
        assertFalse("task" in snapshot.toString())
        assertFalse(snapshot.ref.value in snapshot.ref.toString())
    }

    @Test
    fun `pages are bounded and search keeps exact process identity`() = runTest {
        val store = ProtectedProcessArchives(HarnessDataTestSecrets(), ProcessesTestClock(1000), CommandToggles())
        val refs = (0..4).map { store.store(owner, ended("task_$it"), ProcessArchiveReason.Completed).ref }.toSet()
        val collected = mutableSetOf<ProcessArchiveRef>()
        var cursor: ProcessArchiveRef? = null
        do {
            val page = store.list(after = cursor, limit = 2)
            assertTrue(page.entries.size <= 2)
            collected += page.entries.map { it.ref }
            cursor = page.next
        } while (cursor != null)
        assertEquals(refs, collected)
        val selected = store.list(process = ProcessId("task_3"), after = null, limit = 2)
        assertEquals(listOf(ProcessId("task_3")), selected.entries.map { it.record.id })
        assertNull(selected.next)
        assertFailsWith<IllegalArgumentException> { store.list(null, 101, null) }
    }

    @Test
    fun `orphan snapshots remain readable while completed archival refuses live work`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val store = ProtectedProcessArchives(secrets, ProcessesTestClock(1000), CommandToggles())
        val live = ProcessRecord(ProcessId("unfinished"), at)
        assertFailsWith<IllegalArgumentException> { store.store(owner, live, ProcessArchiveReason.Completed) }
        val snapshot = store.store(owner, live, ProcessArchiveReason.HarnessDeleted)
        assertEquals(ProcessArchiveReason.HarnessDeleted, store.get(snapshot.ref)?.reason)
        assertEquals(live, store.get(snapshot.ref)?.record)
        assertEquals(1, store.listOwned(owner, null, 20, null).entries.size)
    }

    @Test
    fun `storage failures are sanitized and cancellation is preserved`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val store = ProtectedProcessArchives(secrets, ProcessesTestClock(1000), CommandToggles())
        secrets.beforeWrite = { error("secret state must not escape") }
        val failure = assertFailsWith<IllegalStateException> {
            store.store(owner, ended(), ProcessArchiveReason.Completed)
        }
        assertEquals("Protected process archive is unavailable", failure.message)
        assertNull(failure.cause)
        assertTrue(secrets.values.isEmpty())
        secrets.beforeWrite = { throw CancellationException("cancel archive") }
        assertFailsWith<CancellationException> { store.store(owner, ended(), ProcessArchiveReason.Completed) }
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun `unsupported archive data is preserved without exposing stored state`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val store = ProtectedProcessArchives(secrets, ProcessesTestClock(1000), CommandToggles())
        val snapshot = store.store(owner, ended("private_task"), ProcessArchiveReason.Completed)
        val key = secrets.values.keys.single()
        val unsupported = secrets.values.getValue(key).replace("\"v\":1", "\"v\":2")
        secrets.values[key] = unsupported
        val failure = assertFailsWith<IllegalStateException> { store.get(snapshot.ref) }
        assertEquals("Protected process archive is unavailable", failure.message)
        assertNull(failure.cause)
        assertEquals(unsupported, secrets.values[key])
    }

    @Test
    fun `a full archive refuses new evidence and preserves all existing snapshots`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val store = ProtectedProcessArchives(secrets, ProcessesTestClock(1000), CommandToggles())
        var first: ProcessArchiveRef? = null
        repeat(ProtectedProcessArchives.CAPACITY) { index ->
            val ref = store.store(owner, ended("task_$index"), ProcessArchiveReason.Completed).ref
            if (first == null) first = ref
        }
        assertFailsWith<IllegalStateException> {
            store.store(owner, ended("overflow"), ProcessArchiveReason.Completed)
        }
        assertEquals(ProtectedProcessArchives.CAPACITY, secrets.values.size)
        assertEquals(ProcessId("task_0"), store.get(requireNotNull(first))?.record?.id)
    }
}
