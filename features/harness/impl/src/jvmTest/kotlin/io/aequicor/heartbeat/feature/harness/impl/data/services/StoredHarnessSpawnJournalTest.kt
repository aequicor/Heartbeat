package io.aequicor.heartbeat.feature.harness.impl.data.services

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessTestStores
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnOwner
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnRecord
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredHarnessSpawnJournalTest {
    private val directory = Files.createTempDirectory("harness-spawn-journal")
    private val path = directory.resolve("ancestry.db")
    private var database: HarnessAncestryDatabase? = null
    private val harness = HarnessId("harness")
    private val helper = HelperId("helper")
    private val record = HarnessSpawnRecord(
        ActionId("reservation"),
        ActionId("spawn"),
        HarnessSpawnOwner(harness, ItemId("script"), 7),
        dispatchSession,
        RequestId("prompt"),
        RequestId("attach"),
        HarnessCallOrigin(sendChain = mapOf(harness to 3)),
    )

    @AfterTest
    fun close() {
        database?.close()
        check(directory.toFile().deleteRecursively())
    }

    private fun open(): StoredHarnessSpawnJournal {
        database?.close()
        val current = Room.databaseBuilder<HarnessAncestryDatabase>(path.toString())
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*HarnessAncestryDatabaseSpec.migrations.toTypedArray())
            .build()
        database = current
        return StoredHarnessSpawnJournal(SpawnJournalTestStores(current))
    }

    @Test
    fun `grant and helper checkpoint survive reopen and exact grant replay never erases helper`() = runTest {
        val journal = open()
        journal.recordGranted(record)
        assertEquals(listOf(record), open().pending())
        val bound = open().bindHelper(record.reservation, helper)
        assertEquals(record.copy(helper = helper), bound)
        val restored = open()
        restored.recordGranted(record)
        assertEquals(bound, restored.bindHelper(record.reservation, helper))
        assertEquals(listOf(bound), restored.pending())
    }

    @Test
    fun `all immutable identity conflicts and helper replacement preserve original proof`() = runTest {
        val journal = open()
        journal.recordGranted(record)
        for (other in listOf(
            record.copy(action = ActionId("other")),
            record.copy(owner = record.owner.copy(harness = HarnessId("other"))),
            record.copy(owner = record.owner.copy(item = ItemId("other"))),
            record.copy(owner = record.owner.copy(generation = 8)),
            record.copy(parent = null),
            record.copy(request = RequestId("other")),
            record.copy(attachRequest = RequestId("other")),
            record.copy(origin = HarnessCallOrigin()),
        )) {
            assertFailsWith<IllegalStateException> { journal.recordGranted(other) }
            assertFailsWith<IllegalStateException> { journal.settle(other) }
        }
        journal.bindHelper(record.reservation, helper)
        assertFailsWith<IllegalStateException> { journal.bindHelper(record.reservation, HelperId("other")) }
        assertEquals(listOf(record.copy(helper = helper)), journal.pending())
    }

    @Test
    fun `lost grant or bind acknowledgement can be reread and exact concurrent replay converges`() = runTest {
        val journal = open()
        assertFailsWith<IllegalStateException> {
            journal.recordGranted(record)
            error("lost grant ACK")
        }
        List(4) { async { journal.recordGranted(record) } }.awaitAll()
        assertFailsWith<IllegalStateException> {
            journal.bindHelper(record.reservation, helper)
            error("lost binding ACK")
        }
        List(4) { async { journal.bindHelper(record.reservation, helper) } }.awaitAll()
        assertEquals(listOf(record.copy(helper = helper)), open().pending())
    }

    @Test
    fun `initial record rejects helper and binding never resurrects a settled or absent acquisition`() = runTest {
        val journal = open()
        assertFailsWith<IllegalArgumentException> { journal.recordGranted(record.copy(helper = helper)) }
        assertFailsWith<IllegalStateException> { journal.bindHelper(record.reservation, helper) }
        journal.recordGranted(record)
        journal.bindHelper(record.reservation, helper)
        journal.settle(record)
        journal.settle(record)
        assertFailsWith<IllegalStateException> { journal.bindHelper(record.reservation, helper) }
        assertTrue(open().pending().isEmpty())
    }

    @Test
    fun `capacity source restores both preprompt and bound acquisitions without runtime dependencies`() = runTest {
        val journal = open()
        journal.recordGranted(record)
        val second = record.copy(reservation = ActionId("second"), action = ActionId("second"))
        journal.recordGranted(second)
        journal.bindHelper(second.reservation, helper)
        val restored = open()
        val source = HarnessSpawnCapacityRecovery(lazyOf(restored))
        val records = source.reservations()
        assertEquals(2, records.size)
        assertNull(records.single { it.reservation == record.reservation }.helper)
        assertEquals(helper, records.single { it.reservation == second.reservation }.helper)
        assertEquals(setOf(record.action, second.action), records.map { it.owner }.toSet())
        // A crash after confirmed release but before deletion conservatively restores the same slot again.
        assertEquals(records, source.reservations())
        restored.settle(record)
        assertEquals(listOf(helper), source.reservations().map { it.helper })
    }

    @Test
    fun `malformed unresolved row fails entire restore without leaking private metadata`() = runTest {
        open().recordGranted(record)
        database?.close()
        database = null
        BundledSQLiteDriver().open(path.toString()).use {
            it.execSQL("UPDATE spawn_journal SET identity = 'private malformed JSON'")
        }
        val journal = open()
        val error = assertFailsWith<HarnessStorageCorrupt> {
            HarnessSpawnCapacityRecovery(lazyOf(journal)).reservations()
        }
        assertNull(error.cause)
        assertTrue(!error.message.orEmpty().contains("private"))
    }

    @Test
    fun `migration preserves existing ancestry and helper ownership while creating empty journal`() = runTest {
        val migration = MigrationTestHelper(
            Path.of("schemas"),
            path,
            BundledSQLiteDriver(),
            HarnessAncestryDatabase::class,
            HarnessAncestryDatabaseConstructor::initialize,
        )
        migration.createDatabase(2).use { connection ->
            connection.prepare("INSERT INTO request_ancestry VALUES (?, ?, ?)").use { statement ->
                statement.bindText(1, dispatchSession.ancestryKey())
                statement.bindText(2, record.request.value)
                statement.bindText(3, HarnessAncestryCodec.encode(record.origin))
                statement.step()
            }
            connection.execSQL("INSERT INTO helper_bindings VALUES ('helper', 'spawn', 'harness', 'attach', NULL)")
        }
        migration.runMigrationsAndValidate(3, HarnessAncestryDatabaseSpec.migrations).close()
        val journal = open()
        val stores = SpawnJournalTestStores(checkNotNull(database))
        assertEquals(record.origin, StoredHarnessRequestAncestry(stores).lookup(dispatchSession, record.request))
        assertEquals(
            HarnessHelperBinding(helper, record.action, harness, record.attachRequest),
            StoredHarnessHelperBindings(stores).lookup(helper),
        )
        assertTrue(journal.pending().isEmpty())
        journal.recordGranted(record)
        assertEquals(record.copy(helper = helper), journal.bindHelper(record.reservation, helper))
    }
}

private class SpawnJournalTestStores(private val database: HarnessAncestryDatabase) :
    DataStores by HarnessTestStores() {
    @Suppress("UNCHECKED_CAST") // This fixture accepts only the exact database spec associated with its instance.
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T {
        check(spec === HarnessAncestryDatabaseSpec)
        return database as T
    }
}
