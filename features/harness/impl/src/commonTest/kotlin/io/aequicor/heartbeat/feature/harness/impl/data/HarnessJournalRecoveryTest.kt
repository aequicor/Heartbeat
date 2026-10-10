package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class HarnessJournalRecoveryTest {
    @Test
    fun `every interrupted create phase recovers to exactly before or after`() = runTest {
        val stops = listOf("prepared", "record", "index", "committed", "cleaned")
        stops.forEach { stop ->
            val stores = HarnessTestStores()
            stores.libraryValues.after = { action, key, value ->
                val stage = stage(action, key, value)
                if (stage == stop) stores.libraryValues.crash()
            }
            assertFailsWith<HarnessTestCrash> { stores.library().save(storageHarness, storageReceipt) }
            stores.libraryValues.restart()
            val expected = if (stop in setOf("committed", "cleaned")) listOf(storageHarness) else emptyList()
            assertEquals(expected, stores.library().load().harnesses, stop)
            assertNull(stores.libraryValues.values["pending"])
        }
    }

    @Test
    fun `lost commit acknowledgement is success while failure before commit rolls back`() = runTest {
        val stores = HarnessTestStores()
        stores.libraryValues.after = { action, key, value ->
            if (stage(action, key, value) == "committed") error("private code in IO error")
        }
        assertEquals(storageReceipt, stores.library().save(storageHarness, storageReceipt))
        assertEquals(listOf(storageHarness), stores.library().load().harnesses)
        val denied = HarnessTestStores()
        denied.libraryValues.before = { action, key, value ->
            if (stage(action, key, value) == "committed") error("private code")
        }
        val failure = assertFailsWith<HarnessStorageFailure> { denied.library().save(storageHarness, storageReceipt) }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("private code"))
        assertTrue(denied.library().load().harnesses.isEmpty())
    }

    @Test
    fun `lost prepared acknowledgement proves rollback before returning failed`() = runTest {
        val stores = HarnessTestStores()
        stores.libraryValues.after = { action, key, value ->
            if (stage(action, key, value) == "prepared") error("ACK lost")
        }
        assertFailsWith<HarnessStorageFailure> { stores.library().save(storageHarness, storageReceipt) }
        assertTrue(stores.libraryValues.values.isEmpty())
    }

    @Test
    fun `rollback failure keeps evidence and never becomes SaveFailed`() = runTest {
        val stores = HarnessTestStores()
        stores.libraryValues.after = { action, key, _ ->
            if (action == "set" && key == harnessRecordKey(storageHarness.id)) error("write acknowledgement lost")
        }
        stores.libraryValues.before = { action, key, _ ->
            if (action == "remove" && key == harnessRecordKey(storageHarness.id)) error("rollback refused")
        }
        assertFailsWith<HarnessStorageUncertain> { stores.library().save(storageHarness, storageReceipt) }
        assertTrue(stores.libraryValues.values.containsKey("pending"))
        assertFailsWith<HarnessStorageUncertain> { stores.library().load() }
        stores.libraryValues.before = { _, _, _ -> }
        stores.libraryValues.after = { _, _, _ -> }
        assertTrue(stores.library().load().harnesses.isEmpty())
        assertEquals(storageReceipt, stores.library().save(storageHarness, storageReceipt))
    }

    @Test
    fun `unreadable commit outcome retains marker until exact retry can prove success`() = runTest {
        val stores = HarnessTestStores()
        var hasCommitted = false
        stores.libraryValues.after = { action, key, value ->
            if (stage(action, key, value) == "committed") {
                hasCommitted = true
                error("lost acknowledgement")
            }
        }
        stores.libraryValues.before = { action, key, _ ->
            if (hasCommitted && action == "get" && key == "pending") error("temporary read failure")
        }
        assertFailsWith<HarnessStorageUncertain> { stores.library().save(storageHarness, storageReceipt) }
        stores.libraryValues.before = { _, _, _ -> }
        stores.libraryValues.after = { _, _, _ -> }
        assertEquals(storageReceipt, stores.library().save(storageHarness, storageReceipt))
        assertEquals(listOf(storageHarness), stores.library().load().harnesses)
    }

    @Test
    fun `cleanup failure after commit never reports write failure and recovery rolls forward`() = runTest {
        val stores = HarnessTestStores()
        stores.libraryValues.before = { action, key, _ -> if (action == "remove" && key == "pending") error("cleanup") }
        assertEquals(storageReceipt, stores.library().save(storageHarness, storageReceipt))
        assertTrue(stores.libraryValues.values.containsKey("pending"))
        stores.libraryValues.before = { _, _, _ -> }
        assertEquals(listOf(storageHarness), stores.library().load().harnesses)
        assertNull(stores.libraryValues.values["pending"])
    }

    @Test
    fun `interrupted delete rolls back harness index and attachments together`() = runTest {
        val stores = HarnessTestStores()
        val library = stores.library()
        library.save(storageHarness, storageReceipt)
        val attachments = mapOf(storageSession to setOf(storageHarness.id))
        library.saveAttachments(
            HarnessAttachmentWrite(RequestId("attach"), storageHarness.id, storageSession, true, attachments),
        )
        stores.libraryValues.after =
            { action, key, _ -> if (action == "set" && key == "attachments") stores.libraryValues.crash() }
        assertFailsWith<HarnessTestCrash> { library.remove(storageHarness, storageReceipt) }
        stores.libraryValues.restart()
        val recovered = stores.library().load()
        assertEquals(listOf(storageHarness), recovered.harnesses)
        assertEquals(attachments, recovered.attachments)
    }

    @Test
    fun `cancelled write either rolls back or retains its journal and never publishes partial records`() = runTest {
        val stores = HarnessTestStores()
        val entered = CompletableDeferred<Unit>()
        stores.libraryValues.before = { action, key, _ ->
            if (action == "set" && key == harnessRecordKey(
                    storageHarness.id,
                )
            ) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val write = async { stores.library().save(storageHarness, storageReceipt) }
        entered.await()
        write.cancelAndJoin()
        stores.libraryValues.before = { _, _, _ -> }
        assertTrue(stores.library().load().harnesses.isEmpty())
    }

    @Test
    fun `committed recovery restores the original absolute retention rather than restarting its TTL`() = runTest {
        val store = HarnessTestKeyValue(KeyValueSpec("harness_test"))
        val deadline = storageNow + 30.days
        val image = HarnessJournalImage(blob = "pending_image.expiring", expiresAt = deadline)
        store.values["pending_image.expiring"] = "text"
        store.expiries["pending_image.expiring"] = deadline
        val pending = HarnessPendingCommit(
            "operation",
            HarnessCommitPhase.Committed,
            emptyMap(),
            mapOf("record" to image),
        )
        store.values["pending"] = Json.encodeToString(pending)
        assertEquals(
            HarnessRecoveredCommit("operation", HarnessCommitPhase.Committed),
            HarnessKeyValueJournal(store, storageClock).recover(),
        )
        assertEquals(deadline, store.expiries["record"])
        assertEquals("text", store.values["record"])
    }
}

private fun stage(action: String, key: String, value: String?): String? = when {
    action == "set" && key == "pending" -> if (value.orEmpty().contains("Committed")) "committed" else "prepared"
    action == "set" && key.startsWith("harness.") -> "record"
    action == "set" && key == "index" -> "index"
    action == "remove" && key == "pending" -> "cleaned"
    else -> null
}
