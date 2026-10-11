package io.aequicor.heartbeat.feature.harness.impl.data.services

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.RecordRetention
import io.aequicor.heartbeat.core.datastore.RecordRetentions
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessTestStores
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
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
import kotlin.time.Duration.Companion.days

class StoredHarnessHelperBindingsTest {
    private val directory = Files.createTempDirectory("harness-helper-bindings")
    private val path = directory.resolve("ancestry.db")
    private var database: HarnessAncestryDatabase? = null
    private val retentions = HelperBindingTestRetentions()
    private val binding = HarnessHelperBinding(
        HelperId("helper"),
        ActionId("owner"),
        HarnessId("harness"),
        RequestId("attach"),
    )

    @AfterTest
    fun close() {
        database?.close()
        check(directory.toFile().deleteRecursively())
    }

    private fun open(): StoredHarnessHelperBindings {
        database?.close()
        val current = Room.databaseBuilder<HarnessAncestryDatabase>(path.toString())
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*HarnessAncestryDatabaseSpec.migrations.toTypedArray())
            .build()
        database = current
        return StoredHarnessHelperBindings(HelperBindingTestStores(current), retentions)
    }

    @Test
    fun `ownership and first native session survive restart and replay of original bind`() = runTest {
        val repository = open()
        assertNull(repository.lookup(binding.helper))
        repository.bind(binding)
        val bound = repository.bindSession(binding.helper, binding.owner, dispatchSession)
        assertEquals(binding.copy(session = dispatchSession), bound)
        val restored = open()
        restored.bind(binding)
        assertEquals(bound, restored.bindSession(binding.helper, binding.owner, dispatchSession))
        assertEquals(bound, restored.lookup(binding.helper))
    }

    @Test
    fun `conflicting owner harness attachment request or native session never replaces durable proof`() = runTest {
        val repository = open()
        repository.bind(binding)
        for (other in listOf(
            binding.copy(owner = ActionId("other")),
            binding.copy(harness = HarnessId("other")),
            binding.copy(attachRequest = RequestId("other")),
        )) {
            assertFailsWith<IllegalStateException> { repository.bind(other) }
        }
        assertFailsWith<IllegalStateException> {
            repository.bindSession(binding.helper, ActionId("other"), dispatchSession)
        }
        repository.bindSession(binding.helper, binding.owner, dispatchSession)
        assertFailsWith<IllegalStateException> {
            repository.bindSession(binding.helper, binding.owner, dispatchSession.copy(nativeId = "other"))
        }
        assertEquals(binding.copy(session = dispatchSession), repository.lookup(binding.helper))
    }

    @Test
    fun `concurrent binds converge and lost acknowledgement cannot erase identity`() = runTest {
        val repository = open()
        List(4) { async { repository.bind(binding) } }.awaitAll()
        List(4) { async { repository.bindSession(binding.helper, binding.owner, dispatchSession) } }.awaitAll()
        assertFailsWith<IllegalStateException> {
            repository.bind(binding)
            error("lost ACK after committed bind")
        }
        assertEquals(binding.copy(session = dispatchSession), open().lookup(binding.helper))
    }

    @Test
    fun `migration preserves request ancestry and enables helper binding after reopening`() = runTest {
        val migration = MigrationTestHelper(
            Path.of("schemas"),
            path,
            BundledSQLiteDriver(),
            HarnessAncestryDatabase::class,
            HarnessAncestryDatabaseConstructor::initialize,
        )
        val expected = HarnessCallOrigin(true, mapOf(binding.harness to 3))
        migration.createDatabase(1).use { connection ->
            connection.prepare("INSERT INTO request_ancestry VALUES (?, ?, ?)").use { statement ->
                statement.bindText(1, dispatchSession.ancestryKey())
                statement.bindText(2, "R")
                statement.bindText(3, HarnessAncestryCodec.encode(expected))
                statement.step()
            }
        }
        migration.runMigrationsAndValidate(2, HarnessAncestryDatabaseSpec.migrations).close()
        val repository = open()
        val stores = HelperBindingTestStores(checkNotNull(database))
        // Rows exist only after the profile marker was written by the first restriction.
        stores.keyValue(HarnessAncestryMarkerSpec).set(HarnessAncestryMarkerKey, "1")
        val ancestry = StoredHarnessRequestAncestry(stores)
        assertEquals(expected, ancestry.lookup(dispatchSession, RequestId("R")))
        repository.bind(binding)
        assertEquals(binding, repository.lookup(binding.helper))
    }

    @Test
    fun `every session binding renews the sliding retention without changing identity`() = runTest {
        val repository = open()
        repository.bind(binding)
        val dao = checkNotNull(database).helpers()
        assertEquals(expiry(0), dao.lookup(binding.helper.value)?.retention?.expiresAt)
        retentions.now = 10.days.inWholeMilliseconds
        // Replaying the initial bind with a later stamp is idempotent, not an ownership conflict.
        repository.bind(binding)
        assertEquals(expiry(0), dao.lookup(binding.helper.value)?.retention?.expiresAt)
        repository.bindSession(binding.helper, binding.owner, dispatchSession)
        retentions.now = 20.days.inWholeMilliseconds
        repository.bindSession(binding.helper, binding.owner, dispatchSession)
        assertEquals(expiry(20.days.inWholeMilliseconds), dao.lookup(binding.helper.value)?.retention?.expiresAt)
        assertEquals(binding.copy(session = dispatchSession), repository.lookup(binding.helper))
    }

    @Test
    fun `retention migration keeps helper ownership and starts a full retention window`() = runTest {
        val migration = MigrationTestHelper(
            Path.of("schemas"),
            path,
            BundledSQLiteDriver(),
            HarnessAncestryDatabase::class,
            HarnessAncestryDatabaseConstructor::initialize,
        )
        migration.createDatabase(3).use { connection ->
            connection.execSQL("INSERT INTO helper_bindings VALUES ('helper', 'owner', 'harness', 'attach', NULL)")
        }
        val before = System.currentTimeMillis()
        migration.runMigrationsAndValidate(4, HarnessAncestryDatabaseSpec.migrations).close()
        val repository = open()
        assertEquals(binding, repository.lookup(binding.helper))
        val retention = checkNotNull(checkNotNull(database).helpers().lookup(binding.helper.value)).retention
        assertTrue(retention.createdAt >= before / 1000 * 1000)
        assertEquals(retention.createdAt + HELPER_BINDING_RETENTION.inWholeMilliseconds, retention.expiresAt)
        assertNull(retention.event)
    }

    @Test
    fun `corrupt stored native session refuses lookup without exposing raw data`() = runTest {
        open().bind(binding)
        database?.close()
        database = null
        BundledSQLiteDriver().open(path.toString()).use {
            it.execSQL("UPDATE helper_bindings SET session = 'private malformed JSON'")
        }
        val error = assertFailsWith<HarnessStorageCorrupt> { open().lookup(binding.helper) }
        assertNull(error.cause)
        assertTrue(!error.message.orEmpty().contains("private"))
    }
}

private fun expiry(writtenAt: Long): Long = writtenAt + HELPER_BINDING_RETENTION.inWholeMilliseconds

/** Stamps rows at a controllable time; only expiring retention is used by helper bindings. */
internal class HelperBindingTestRetentions : RecordRetentions {
    var now = 0L
    override fun stamp(retention: Retention): RecordRetention = RecordRetention(
        now,
        (retention.expiry as? Expiry.After)?.let { now + it.ttl.inWholeMilliseconds },
        retention.event?.name,
    )
}

private class HelperBindingTestStores(private val database: HarnessAncestryDatabase) :
    DataStores by HarnessTestStores() {
    @Suppress("UNCHECKED_CAST") // This fixture accepts only the exact database spec associated with its instance.
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T {
        check(spec === HarnessAncestryDatabaseSpec)
        return database as T
    }
}
