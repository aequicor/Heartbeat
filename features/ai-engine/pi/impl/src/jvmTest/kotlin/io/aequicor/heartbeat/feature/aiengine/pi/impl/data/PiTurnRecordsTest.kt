package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PiTurnRecordsTest {
    @Test
    fun `stale completion preserves the first receipt and cannot clear a newer request`() = runTest {
        val fixture = fixture(this)
        val session = fixture.session
        val records = MemoryPiTurnRecords()
        val journal = PiTurnJournal(records, session.ref, session.route, "owner")
        val first = Turn(TurnId("first"), prompt("first").id, RuntimeTarget)
        val next = Turn(TurnId("next"), prompt("next").id, RuntimeTarget)
        journal.begin(first, TrustLevel.Ask)
        journal.finish(first.id, TurnOutcome.Completed)
        assertEquals(TurnOutcome.Completed, journal.finish(first.id, TurnOutcome.Unknown)?.turn?.outcome)
        journal.begin(next, TrustLevel.Ask)
        assertEquals(TurnOutcome.Completed, journal.finish(first.id, TurnOutcome.Unknown)?.turn?.outcome)
        assertEquals(next, records.get(session.ref)?.active?.turn)
    }

    @Test
    fun `different credential fingerprint cannot read or replace an owned turn`() = runTest {
        val fixture = fixture(this)
        val session = fixture.session
        val records = MemoryPiTurnRecords()
        val journal = PiTurnJournal(records, session.ref, session.route, "owner")
        val turn = Turn(TurnId("logical"), prompt("request").id, RuntimeTarget)
        journal.begin(turn, TrustLevel.Ask)
        val changed = PiTurnJournal(records, session.ref, session.route, "different-owner")
        assertFailsWith<EngineException> { changed.restore() }
        assertFailsWith<EngineException> { changed.finish(turn.id, TurnOutcome.Unknown) }
        assertEquals(turn, journal.restore()?.active?.turn)
    }

    @Test
    fun `stored correlation survives reopening and isolates the full session reference`() = runTest {
        val stores = TurnStores()
        val fixture = fixture(this)
        val session = fixture.session
        val records = StoredPiTurnRecords(stores)
        val journal = PiTurnJournal(records, session.ref, session.route, "owner")
        val turn = Turn(TurnId("logical"), prompt("request").id, RuntimeTarget)
        journal.begin(turn, TrustLevel.Ask)
        val reopened = StoredPiTurnRecords(stores)
        assertEquals(turn, reopened.get(session.ref)?.active?.turn)
        assertNull(reopened.get(session.ref.copy(source = SessionSourceId("another"))))
        journal.finish(turn.id, TurnOutcome.Completed)
        assertNull(reopened.get(session.ref)?.active)
        assertEquals(TurnOutcome.Completed, reopened.get(session.ref)?.last?.turn?.outcome)
    }

    @Test
    fun `missing or corrupt required record fails closed without exposing its contents`() = runTest {
        for (corrupt in listOf<String?>(null, "private damaged record")) {
            val stores = TurnStores()
            val fixture = fixture(this)
            val session = fixture.session
            val records = StoredPiTurnRecords(stores)
            val journal = PiTurnJournal(records, session.ref, session.route, "owner")
            journal.begin(Turn(TurnId("logical"), prompt("request").id, RuntimeTarget), TrustLevel.Ask)
            val key = "turn_${piTurnKey(session.ref)}"
            if (corrupt == null) stores.values.remove(key) else stores.values[key] = corrupt
            val restored = PiTurnJournal(StoredPiTurnRecords(stores), session.ref, session.route, "owner")
            val failure = assertFailsWith<EngineException> { restored.restore() }
            assertFalse(failure.toString().contains("private damaged record"))
        }
    }

    @Test
    fun `failed terminal write remains retryable and cannot publish a terminal record`() = runTest {
        val stores = TurnStores()
        val fixture = fixture(this)
        val session = fixture.session
        val records = StoredPiTurnRecords(stores)
        val journal = PiTurnJournal(records, session.ref, session.route, "owner")
        val turn = Turn(TurnId("logical"), prompt("request").id, RuntimeTarget)
        journal.begin(turn, TrustLevel.Ask)
        stores.isFailing = true
        assertFailsWith<EngineException> { journal.finish(turn.id, TurnOutcome.Cancelled) }
        assertEquals(turn, records.get(session.ref)?.active?.turn)
        assertNull(records.get(session.ref)?.last)
        stores.isFailing = false
        journal.finish(turn.id, TurnOutcome.Cancelled)
        assertEquals(TurnOutcome.Cancelled, records.get(session.ref)?.last?.turn?.outcome)
    }
}

private class TurnStores : DataStores {
    val values = mutableMapOf<String, Any>()
    var isFailing = false
    override val owner: StorageOwner = StorageOwner.App
    override fun filesDirectory(name: String): String = error("Unused")
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Unused")
    override suspend fun fire(event: DataEvent) = Unit
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = object : KeyValueStore {
        override val spec = spec

        // The production key carries its type; this fake stores only values written using that key.
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> get(key: StoreKey<T>): T? = values[key.name] as T?
        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = flowOf(null)
        override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
            check(!isFailing) { "Storage unavailable" }
            values[key.name] = value
        }
        override suspend fun remove(key: StoreKey<*>) {
            values.remove(key.name)
        }
        override suspend fun clear() = values.clear()
    }
}
