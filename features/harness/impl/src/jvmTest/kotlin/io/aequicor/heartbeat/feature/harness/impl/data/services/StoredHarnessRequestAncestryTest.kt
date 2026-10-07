package io.aequicor.heartbeat.feature.harness.impl.data.services

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessTestStores
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredHarnessRequestAncestryTest {
    private val directory = Files.createTempDirectory("harness-ancestry-test").toFile()
    private val path = directory.resolve("ancestry.db").absolutePath
    private var database: HarnessAncestryDatabase? = null

    @AfterTest
    fun close() {
        database?.close()
        check(directory.deleteRecursively())
    }

    private fun open(): StoredHarnessRequestAncestry {
        database?.close()
        val current = Room.databaseBuilder<HarnessAncestryDatabase>(path)
            .setDriver(BundledSQLiteDriver())
            .build()
        database = current
        return StoredHarnessRequestAncestry(AncestryTestStores(current))
    }

    @Test
    fun `accepted request survives database and repository restart for late causal references`() = runTest {
        val expected = HarnessCallOrigin(true, mapOf(OWNER to 3))
        open().restrict(dispatchSession, REQUEST, expected)
        val restarted = open()
        assertEquals(expected, restarted.lookup(dispatchSession, REQUEST))
        // Neither terminal hook delivery nor a scheduler wake row is needed to resolve the old exact request.
        assertEquals(expected, restarted.lookup(dispatchSession, REQUEST))
        assertNull(restarted.lookup(dispatchSession, RequestId("later-user-request")))
    }

    @Test
    fun `concurrent restrictions merge without weakening either owner`() = runTest {
        val repository = open()
        val other = HarnessId("another")
        listOf(
            HarnessCallOrigin(true, mapOf(OWNER to 1)),
            HarnessCallOrigin(false, mapOf(OWNER to 3)),
            HarnessCallOrigin(false, mapOf(other to 2)),
        ).map { origin -> async { repository.restrict(dispatchSession, REQUEST, origin) } }.awaitAll()
        repository.restrict(dispatchSession, REQUEST, HarnessCallOrigin(false, mapOf(OWNER to 0)))
        assertEquals(HarnessCallOrigin(true, mapOf(OWNER to 3, other to 2)), open().lookup(dispatchSession, REQUEST))
    }

    @Test
    fun `same request is isolated by every session identity field`() = runTest {
        val repository = open()
        repository.restrict(dispatchSession, REQUEST, HarnessCallOrigin(true))
        val distinct = listOf(
            dispatchSession.copy(engine = EngineId("other")),
            dispatchSession.copy(source = SessionSourceId("other")),
            dispatchSession.copy(nativeId = "other"),
        )
        distinct.forEach { assertNull(repository.lookup(it, REQUEST)) }
        assertTrue(repository.lookup(dispatchSession, REQUEST)?.isHookRestricted == true)
    }

    @Test
    fun `lost acknowledgement can be retried without erasing committed restrictions`() = runTest {
        val repository = open()
        assertFailsWith<IllegalStateException> {
            repository.restrict(dispatchSession, REQUEST, HarnessCallOrigin(true, mapOf(OWNER to 3)))
            error("Simulated acknowledgement loss after commit")
        }
        val restarted = open()
        restarted.restrict(dispatchSession, REQUEST, HarnessCallOrigin(false, mapOf(OWNER to 1)))
        assertEquals(HarnessCallOrigin(true, mapOf(OWNER to 3)), restarted.lookup(dispatchSession, REQUEST))
    }

    @Test
    fun `corruption refuses both lookup and merge without neutral fallback`() = runTest {
        open().restrict(dispatchSession, REQUEST, HarnessCallOrigin(true))
        database?.close()
        database = null
        val connection = BundledSQLiteDriver().open(path)
        try {
            connection.execSQL("UPDATE request_ancestry SET origin = '{}'")
        } finally {
            connection.close()
        }
        val restarted = open()
        assertFailsWith<kotlinx.serialization.SerializationException> { restarted.lookup(dispatchSession, REQUEST) }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            restarted.restrict(dispatchSession, REQUEST, HarnessCallOrigin(true))
        }
    }

    @Test
    fun `neutral registration neither opens database nor creates a row`() = runTest {
        val current = Room.inMemoryDatabaseBuilder<HarnessAncestryDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        database = current
        val stores = AncestryTestStores(current)
        val repository = StoredHarnessRequestAncestry(stores)
        repository.restrict(dispatchSession, REQUEST, HarnessCallOrigin())
        assertEquals(0, stores.opens)
        assertNull(repository.lookup(dispatchSession, REQUEST))
        assertEquals(1, stores.opens)
    }
}

private class AncestryTestStores(private val database: HarnessAncestryDatabase) : DataStores by HarnessTestStores() {
    var opens = 0

    @Suppress("UNCHECKED_CAST") // This fixture accepts only the exact database spec associated with its instance.
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T {
        check(spec === HarnessAncestryDatabaseSpec)
        opens++
        return database as T
    }
}

private val OWNER = HarnessId("owner")
private val REQUEST = RequestId("request")
