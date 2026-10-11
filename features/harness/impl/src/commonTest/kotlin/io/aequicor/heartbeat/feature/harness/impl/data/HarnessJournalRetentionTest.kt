package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class HarnessJournalRetentionTest {
    @Test
    fun `committed marker cannot retain either the old permanent source or expiring result`() = runTest {
        val clock = JournalClock()
        val store = HarnessTestKeyValue(KeyValueSpec("journal_retention"), clock)
        val journal = HarnessKeyValueJournal(store, clock)
        val deadline = storageNow + 30.days
        store.values["record"] = "running source"
        store.before = { action, key, _ -> if (action == "remove" && key == "pending") error("cleanup denied") }
        journal.commit(
            "operation",
            mapOf("record" to HarnessRawValue("running source")),
            mapOf("record" to HarnessRawValue("terminal result", deadline)),
        )
        val marker = checkNotNull(store.values["pending"])
        assertFalse(marker.contains("running source"))
        assertFalse(marker.contains("terminal result"))
        val decoded = Json.decodeFromString<HarnessPendingCommit>(marker)
        assertTrue(decoded.before.isEmpty())
        val blob = checkNotNull(decoded.after.getValue("record")?.blob)
        assertEquals(deadline, store.expiries[blob])
        store.before = { _, _, _ -> }
        clock.current = deadline
        journal.recover()
        assertNull(store.values["record"])
        assertNull(store.values["pending"])
        assertNull(store.values[blob])
    }

    @Test
    fun `prepared rollback restores Running even after the proposed terminal blob has expired`() = runTest {
        val clock = JournalClock()
        val store = HarnessTestKeyValue(KeyValueSpec("journal_retention"), clock)
        val journal = HarnessKeyValueJournal(store, clock)
        store.values["record"] = "running"
        val deadline = storageNow + 30.days
        store.after = { action, key, _ -> if (action == "set" && key == "record") store.crash() }
        assertFailsWith<HarnessTestCrash> {
            journal.commit(
                "operation",
                mapOf("record" to HarnessRawValue("running")),
                mapOf("record" to HarnessRawValue("terminal", deadline)),
            )
        }
        val marker = Json.decodeFromString<HarnessPendingCommit>(checkNotNull(store.values["pending"]))
        assertEquals(HarnessCommitPhase.Prepared, marker.phase)
        assertEquals("running", marker.before["record"]?.text)
        assertNull(marker.after["record"]?.text)
        store.restart()
        clock.current = deadline
        journal.recover()
        assertEquals("running", store.values["record"])
        assertNull(store.expiries["record"])
    }

    @Test
    fun `missing unexpired blob never becomes absence proof but missing expired blob does`() = runTest {
        val clock = JournalClock()
        val store = HarnessTestKeyValue(KeyValueSpec("journal_retention"), clock)
        val deadline = storageNow + 30.days
        val marker = HarnessPendingCommit(
            "operation",
            HarnessCommitPhase.Committed,
            emptyMap(),
            mapOf("record" to HarnessJournalImage(blob = "pending_image.missing", expiresAt = deadline)),
        )
        store.values["pending"] = Json.encodeToString(marker)
        assertFailsWith<HarnessStorageUncertain> { HarnessKeyValueJournal(store, clock).recover() }
        assertTrue(store.values.containsKey("pending"))
        clock.current = deadline
        HarnessKeyValueJournal(store, clock).recover()
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `crash while writing blobs leaves original records and only naturally expiring orphan evidence`() = runTest {
        val store = HarnessTestKeyValue(KeyValueSpec("journal_retention"))
        val deadline = storageNow + 30.days
        store.values["record"] = "running"
        store.after = { action, key, _ ->
            if (action == "set" && key.startsWith("pending_image.")) store.crash()
        }
        assertFailsWith<HarnessTestCrash> {
            HarnessKeyValueJournal(
                store,
                storageClock,
            ).commit(
                "operation",
                mapOf("record" to HarnessRawValue("running")),
                mapOf("record" to HarnessRawValue("terminal", deadline)),
            )
        }
        assertEquals("running", store.values["record"])
        assertNull(store.values["pending"])
        val blob = store.values.keys.single { it.startsWith("pending_image.") }
        assertEquals(deadline, store.expiries[blob])
        store.restart()
        assertNull(HarnessKeyValueJournal(store, storageClock).recover())
    }

    @Test
    fun `cancel after Committed preserves the committed state for recovery`() = runTest {
        val stores = HarnessTestStores()
        val entered = CompletableDeferred<Unit>()
        stores.libraryValues.after = { action, key, value ->
            if (action == "set" && key == "pending" && value.orEmpty().contains("Committed")) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val write = async { stores.library().save(storageHarness, storageReceipt) }
        entered.await()
        write.cancelAndJoin()
        stores.libraryValues.after = { _, _, _ -> }
        assertEquals(listOf(storageHarness), stores.library().load().harnesses)
    }
}

private class JournalClock : Clock {
    var current = storageNow
    override fun now() = current
}
