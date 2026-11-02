package io.aequicor.heartbeat.feature.organicai.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.api.Work
import io.aequicor.heartbeat.feature.organicai.api.isStopped
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeClaim
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeEnd
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeStage
import io.aequicor.heartbeat.feature.organicai.impl.domain.cellExecution
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueOrganismJournalTest {
    private val stores = MemoryStores()
    private val journal = KeyValueOrganismJournal(stores, TestDispatchers)

    @Test
    fun `release proof survives restart and a later working epoch invalidates it`() = runTest {
        val original = organism().let { it.copy(cells = it.cells.map { cell -> cell.copy(session = null) }) }
        val incarnation = original.incarnation!!
        journal.save(original)
        val execution = original.cellExecution(CellId.ZYGOTE)!!
        val claim = OrganicNativeClaim(
            "unsent",
            execution.instance.id,
            execution,
            TARGET,
            null,
            (original.cells.first().phase as CellPhase.Working).request,
            "work",
        )
        val reserved = journal.reserve(original.id, incarnation, claim)
        journal.advance(
            original.id,
            incarnation,
            reserved,
            reserved.copy(
                stage = OrganicNativeStage.Settled,
                end = OrganicNativeEnd.NotEntered,
                revision = 1,
            ),
        )
        val completed = original.copy(status = OrganismStatus.Completed("done"), version = 2)
        journal.save(completed)
        assertFailsWith<IllegalStateException> {
            journal.released(original.id, incarnation, completed.version, setOf(session("unaccounted")))
        }
        journal.released(original.id, incarnation, completed.version, emptySet())
        assertEquals(
            SessionRelease.Released,
            KeyValueOrganismJournal(stores, TestDispatchers).read(original.id)?.sessions,
        )
        assertFalse(stores.store.retentions.getValue("organism.o1").isPermanent)
        journal.save(original.copy(version = 3))
        assertTrue(stores.store.retentions.getValue("organism.o1").isPermanent)
        journal.save(completed.copy(version = 4))
        assertEquals(
            SessionRelease.Unconfirmed,
            KeyValueOrganismJournal(stores, TestDispatchers).read(original.id)?.sessions,
        )
    }

    @Test
    fun `native receipts survive snapshot writes and stale callbacks cannot alter them`() = runTest {
        val original = organism()
        journal.save(original)
        val incarnation = original.incarnation!!
        val execution = original.cellExecution(CellId.ZYGOTE)!!
        val claim = OrganicNativeClaim(
            "claim",
            execution.instance.id,
            execution,
            TARGET,
            null,
            (original.cells.first().phase as CellPhase.Working).request,
            "work",
        )
        val reserved = journal.reserve(original.id, incarnation, claim)
        val opening = reserved.copy(stage = OrganicNativeStage.Opening, revision = 1)
        journal.advance(original.id, incarnation, reserved, opening)
        assertFailsWith<IllegalStateException> {
            journal.advance(original.id, incarnation, reserved, opening)
        }
        journal.save(original.copy(version = 2, goal = "new snapshot"))
        val restarted = KeyValueOrganismJournal(stores, TestDispatchers)
        assertEquals(listOf(opening), restarted.attempts(original.id, incarnation))
        assertEquals("new snapshot", restarted.read(original.id)?.goal)
        assertFailsWith<IllegalStateException> {
            restarted.advance(
                original.id,
                incarnation,
                reserved,
                reserved.copy(
                    stage = OrganicNativeStage.Settled,
                    end = OrganicNativeEnd.NotEntered,
                    revision = 1,
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            restarted.advance(
                original.id,
                incarnation,
                opening,
                opening.copy(
                    stage = OrganicNativeStage.Settled,
                    end = OrganicNativeEnd.NotEntered,
                    revision = 2,
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            restarted.reserve(original.id, incarnation, claim.copy(prompt = "changed"))
        }
        assertEquals(listOf(opening), restarted.attempts(original.id, incarnation))
    }

    @Test
    fun `future envelope is retained and cannot be overwritten as a legacy snapshot`() = runTest {
        val original = organism()
        journal.save(original)
        stores.store.values["organism.o1"] = stores.store.values.getValue("organism.o1").toString()
            .replace("\"schema\":1", "\"schema\":2")
        assertTrue(journal.load().isEmpty())
        assertFailsWith<IllegalArgumentException> { journal.read(original.id) }
        assertFailsWith<IllegalArgumentException> { journal.save(original.copy(version = 2)) }
    }

    @Test
    fun `physical read failure cannot recover empty authority or overwrite the saved incarnation`() = runTest {
        val original = organism()
        journal.save(original)
        val saved = stores.store.values.toMap()
        stores.store.isUnreadable = true
        val cold = KeyValueOrganismJournal(stores, TestDispatchers)
        assertFailsWith<IllegalStateException> { cold.read(original.id) }
        assertFailsWith<IllegalStateException> { cold.load() }
        assertFailsWith<IllegalStateException> { cold.save(original.copy(incarnation = "replacement")) }
        assertEquals(KeyValueReadPolicy.Strict, stores.store.spec.readPolicy)
        assertEquals(saved, stores.store.values)
    }

    @Test
    fun `strict authority lookup preserves nonce and refuses corrupt or mismatched records`() = runTest {
        val known = organism().copy(incarnation = "first-life")
        journal.save(known)
        assertEquals(known, KeyValueOrganismJournal(stores, TestDispatchers).read(known.id))
        assertNull(journal.read(OrganismId("missing")))
        stores.store.values["organism.other"] = stores.store.values.getValue("organism.o1")
        assertFailsWith<IllegalArgumentException> { journal.read(OrganismId("other")) }
        stores.store.values["organism.o1"] = "broken"
        assertFailsWith<IllegalArgumentException> { journal.read(known.id) }
    }

    @Test
    fun `legacy records never acquire a nonce from decoding`() = runTest {
        journal.save(organism().copy(incarnation = null))
        assertNull(KeyValueOrganismJournal(stores, TestDispatchers).read(OrganismId("o1"))?.incarnation)
    }

    @Test
    fun `organisms survive a round trip through the journal`() = runTest {
        val organism = organism().copy(attachments = listOf(ResourceRef("attachment:image", "image/png")))
        journal.save(organism)
        assertEquals(setOf(organism.id.value), stores.store.values["index"])
        assertEquals(listOf(organism), KeyValueOrganismJournal(stores, TestDispatchers).load())
    }

    @Test
    fun `the harness choice survives the journal and an empty one is not written`() = runTest {
        val chosen = organism().copy(harnesses = setOf("compose", "verify"))
        journal.save(chosen)
        assertTrue("\"harnesses\"" in stores.store.values["organism.o1"].toString())
        assertEquals(listOf(chosen), KeyValueOrganismJournal(stores, TestDispatchers).load())

        val plain = MemoryStores()
        KeyValueOrganismJournal(plain, TestDispatchers).save(organism())
        assertFalse("harnesses" in plain.store.values["organism.o1"].toString())
    }

    @Test
    fun `a record written before harnesses existed reads as an empty choice`() = runTest {
        journal.save(organism().copy(harnesses = setOf("compose")))
        val record = stores.store.values["organism.o1"].toString()
        stores.store.values["organism.o1"] = record.replace(Regex(",?\"harnesses\":\\[[^\\]]*\\]"), "")
        assertFalse("harnesses" in stores.store.values["organism.o1"].toString())
        val loaded = KeyValueOrganismJournal(stores, TestDispatchers).load().single()
        assertEquals(emptySet(), loaded.harnesses)
    }

    @Test
    fun `a record with a harness choice no organism could hold is kept unread`() = runTest {
        journal.save(organism().copy(harnesses = setOf("compose")))
        val record = stores.store.values["organism.o1"].toString()
        stores.store.values["organism.o1"] = record.replace("\"compose\"", "\" \"")
        assertTrue(KeyValueOrganismJournal(stores, TestDispatchers).load().isEmpty())
        assertTrue("organism.o1" in stores.store.values)
        assertEquals(setOf("o1"), stores.store.values["index"])
    }

    @Test
    fun `a pending follow-up survives reopening the journal with its inputs`() = runTest {
        val initial = organism()
        val work = Work.FollowUp("Check the revised design", listOf(ResourceRef("attachment:new", "image/png")))
        val pending = initial.copy(
            cells = initial.cells.map {
                it.copy(phase = (it.phase as CellPhase.Working).copy(work = work))
            },
        )
        journal.save(pending)
        assertEquals(listOf(pending), KeyValueOrganismJournal(stores, TestDispatchers).load())
    }

    @Test
    fun `an older snapshot never replaces a newer one`() = runTest {
        val newer = organism().copy(version = 5)
        journal.save(newer)
        journal.save(newer.copy(version = 4, goal = "stale"))
        journal.save(newer.copy(version = 5, goal = "same version"))
        assertEquals(listOf(newer), journal.load())
        journal.save(newer.copy(version = 6, goal = "changed"))
        assertEquals("changed", journal.load().single().goal)
    }

    @Test
    fun `cold writers cannot replace newer state or another incarnation`() = runTest {
        val original = organism().copy(version = 9)
        journal.save(original)
        val cold = KeyValueOrganismJournal(stores, TestDispatchers)
        cold.save(original.copy(version = 2))
        assertEquals(original, cold.read(original.id))
        assertFailsWith<IllegalStateException> { cold.save(original.copy(incarnation = "replacement", version = 10)) }
        assertEquals(original, cold.read(original.id))
        stores.store.values["organism.o1"] = "corrupt"
        assertFailsWith<IllegalArgumentException> { cold.save(original.copy(version = 10)) }
        assertEquals("corrupt", stores.store.values["organism.o1"])
    }

    @Test
    fun `a loaded version guards later writes of a fresh journal`() = runTest {
        journal.save(organism().copy(version = 3))
        val reopened = KeyValueOrganismJournal(stores, TestDispatchers)
        reopened.load()
        reopened.save(organism().copy(version = 2, goal = "stale"))
        assertEquals(3, reopened.load().single().version)
    }

    @Test
    fun `an unreadable record is kept while an expired one leaves the index`() = runTest {
        journal.save(organism())
        stores.store.values["organism.broken"] = """{"id":{"value":"broken"},"goal":""}"""
        stores.store.values["index"] = setOf("o1", "broken", "expired")
        val loaded = KeyValueOrganismJournal(stores, TestDispatchers).load()
        assertEquals(listOf(OrganismId("o1")), loaded.map { it.id })
        assertEquals(setOf("o1", "broken"), stores.store.values["index"])
        assertTrue("organism.broken" in stores.store.values)
    }

    @Test
    fun `an interrupted first save leaves no record outside the index`() = runTest {
        stores.store.failing = "organism.o1"
        assertFailsWith<IllegalStateException> { journal.save(organism()) }
        assertEquals(setOf("o1"), stores.store.values["index"])
        assertTrue(KeyValueOrganismJournal(stores, TestDispatchers).load().isEmpty())
        assertEquals(emptySet<String>(), stores.store.values["index"])
    }

    @Test
    fun `an aborted organism without native proof remains unconfirmed after restart`() = runTest {
        val ended = organism().copy(status = OrganismStatus.Aborted, sessions = SessionRelease.Releasing, version = 2)
        journal.save(ended)
        // A missing process or handle cannot prove that old native work stopped.
        val restored = KeyValueOrganismJournal(stores, TestDispatchers).load().single()
        assertEquals(ended.copy(sessions = SessionRelease.Unconfirmed), restored)
        assertFalse(restored.isStopped)
    }

    @Test
    fun `ended organisms without native cleanup proof remain permanent`() = runTest {
        journal.save(organism())
        assertTrue(stores.store.retentions.getValue("organism.o1").isPermanent)
        journal.save(organism().copy(status = OrganismStatus.Aborted, version = 2))
        assertTrue(stores.store.retentions.getValue("organism.o1").isPermanent)
    }

    @Test
    fun `the default model is the user's selection`() = runTest {
        var selection = ModelSelection()
        val selections = object : ModelSelections {
            override fun observe(): Flow<ModelSelection> = flowOf(selection)
            override suspend fun update(change: (ModelSelection) -> ModelSelection) = change(selection)
        }
        assertNull(SelectedModelTargets(selections).default())
        selection = ModelSelection().withDefault(TARGET)
        assertEquals(TARGET, SelectedModelTargets(selections).default())
    }

    internal object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
    }

    internal class MemoryStores : DataStores {
        val store = MemoryStore()
        override val owner: StorageOwner = StorageOwner.App
        override fun filesDirectory(name: String): String = error("unused")
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = store.also { it.spec = spec }
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
        override suspend fun fire(event: DataEvent) = Unit
    }

    internal class MemoryStore : KeyValueStore {
        override lateinit var spec: KeyValueSpec
        val values = mutableMapOf<String, Any>()
        val retentions = mutableMapOf<String, Retention>()

        /** A key whose writes fail, as an interrupted write would. */
        var failing: String? = null
        var isUnreadable = false

        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = emptyFlow()

        @Suppress("UNCHECKED_CAST") // Test fake: one value type per key.
        override suspend fun <T : Any> get(key: StoreKey<T>): T? {
            if (isUnreadable) {
                check(spec.readPolicy != KeyValueReadPolicy.Strict) { "physical store read failed" }
                return null
            }
            return values[key.name] as T?
        }

        override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
            check(key.name != failing) { "write interrupted" }
            values[key.name] = value
            retentions[key.name] = retention
        }

        override suspend fun remove(key: StoreKey<*>) {
            values.remove(key.name)
        }

        override suspend fun clear() = values.clear()
    }
}
