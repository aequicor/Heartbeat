package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.RecordRetention
import io.aequicor.heartbeat.core.datastore.RecordRetentions
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.LedgerUpdate
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.updated
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class ContextOptimizationDatabaseTest {
    private val database = Room.inMemoryDatabaseBuilder<ContextOptimizationDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
    private val clock = FixedClock(Instant.fromEpochSeconds(1_000_000))
    private val retentions = TestRetentions(clock)
    private val ledgers = RoomContextLedgers(TestStores(database), retentions, clock)
    private val outputs = RoomContextOutputs(TestStores(database), retentions, clock)

    @AfterTest
    fun close() {
        database.close()
    }

    @Test
    fun `ledgers are saved and deleted per session`() = runTest {
        ledgers.save(One, ledger("one"))
        ledgers.save(Two, ledger("two"))
        ledgers.save(One, ledger("one again"))
        ledgers.delete(Two)

        assertEquals(mapOf(One to ledger("one again")), ledgers.load())
    }

    @Test
    fun `an unreadable ledger row is skipped and kept while other rows load and save`() = runTest {
        val broken = LedgerEntity("""{"broken":""", "{}", 0, retentions.stamp(Retention.Permanent))
        database.ledgers().save(broken, max = 10)
        ledgers.save(One, ledger("one"))

        assertEquals(mapOf(One to ledger("one")), ledgers.load())
        ledgers.save(Two, ledger("two"))
        assertEquals(setOf(One, Two), ledgers.load().keys)
        assertEquals(3, database.ledgers().all(clock.now().toEpochMilliseconds()).size)
    }

    @Test
    fun `only the most recently updated ledgers stay`() = runTest {
        repeat(MAX_LEDGERS + 5) { index ->
            val session = SessionRef(EngineId("pi"), SessionSourceId("s"), "session-$index")
            ledgers.save(session, ledger("goal", at = Instant.fromEpochSeconds(index.toLong())))
        }

        val stored = ledgers.load().keys.map { it.nativeId }.toSet()
        assertEquals(MAX_LEDGERS, stored.size)
        assertEquals((5 until MAX_LEDGERS + 5).map { "session-$it" }.toSet(), stored)
    }

    @Test
    fun `an expired ledger is not loaded`() = runTest {
        retentions.expiresAt = clock.now().toEpochMilliseconds() - 1
        ledgers.save(One, ledger("old"))

        assertEquals(emptyMap(), ledgers.load())
    }

    @Test
    fun `an output is stored and read back once`() = runTest {
        val stored = outputs.put(StoredOutput(One, "run_command", "output text"))

        assertEquals(StoredOutput(One, "run_command", "output text"), outputs.get(stored.id))
        assertNull(outputs.get("missing"))
    }

    @Test
    fun `the oldest outputs are dropped beyond the count limit`() = runTest {
        val first = outputs.put(StoredOutput(One, "tool", "first")).id
        repeat(MAX_OUTPUTS) { outputs.put(StoredOutput(One, "tool", "next $it")) }

        assertNull(outputs.get(first))
    }

    @Test
    fun `the oldest outputs are dropped beyond the total size`() = runTest {
        val large = "x".repeat(MAX_OUTPUT_CHARS)
        val ids = List(MAX_TOTAL_CHARS / MAX_OUTPUT_CHARS + 1) { outputs.put(StoredOutput(One, "tool", large)).id }

        assertNull(outputs.get(ids.first()))
        assertNotNull(outputs.get(ids.last()))
        assertNotNull(outputs.get(ids[1]))
    }

    @Test
    fun `an expired output is not read`() = runTest {
        retentions.expiresAt = clock.now().toEpochMilliseconds() - 1
        val stored = outputs.put(StoredOutput(One, "tool", "text"))

        assertNull(outputs.get(stored.id))
    }

    private fun ledger(goal: String, at: Instant = Instant.fromEpochSeconds(1)) =
        null.updated(LedgerUpdate(goal = goal), at)

    private class FixedClock(private val now: Instant) : Clock {
        override fun now(): Instant = now
    }

    private class TestRetentions(private val clock: Clock) : RecordRetentions {
        var expiresAt: Long? = null

        override fun stamp(retention: Retention): RecordRetention {
            val now = clock.now().toEpochMilliseconds()
            val expiry = (retention.expiry as? Expiry.After)?.let { now + it.ttl.inWholeMilliseconds }
            return RecordRetention(now, expiresAt ?: expiry, null)
        }
    }

    private class TestStores(private val database: ContextOptimizationDatabase) : DataStores {
        override val owner: StorageOwner = StorageOwner.App

        override fun filesDirectory(name: String): String = error("unused")

        override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("unused")

        @Suppress("UNCHECKED_CAST") // The repositories open only this database.
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = database as T

        override suspend fun fire(event: DataEvent) = Unit
    }

    private companion object {
        const val MAX_LEDGERS = 200
        const val MAX_OUTPUTS = 64
        const val MAX_OUTPUT_CHARS = 500_000
        const val MAX_TOTAL_CHARS = 4_000_000
        val One = SessionRef(EngineId("pi"), SessionSourceId("s"), "one")
        val Two = SessionRef(EngineId("pi"), SessionSourceId("s"), "two")
    }
}
