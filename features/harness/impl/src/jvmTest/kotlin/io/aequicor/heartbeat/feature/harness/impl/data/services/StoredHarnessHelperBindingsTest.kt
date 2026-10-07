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

class StoredHarnessHelperBindingsTest {
    private val directory = Files.createTempDirectory("harness-helper-bindings")
    private val path = directory.resolve("ancestry.db")
    private var database: HarnessAncestryDatabase? = null
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
        return StoredHarnessHelperBindings(HelperBindingTestStores(current))
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
        val ancestry = StoredHarnessRequestAncestry(HelperBindingTestStores(checkNotNull(database)))
        assertEquals(expected, ancestry.lookup(dispatchSession, RequestId("R")))
        repository.bind(binding)
        assertEquals(binding, repository.lookup(binding.helper))
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

private class HelperBindingTestStores(private val database: HarnessAncestryDatabase) :
    DataStores by HarnessTestStores() {
    @Suppress("UNCHECKED_CAST") // This fixture accepts only the exact database spec associated with its instance.
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T {
        check(spec === HarnessAncestryDatabaseSpec)
        return database as T
    }
}
