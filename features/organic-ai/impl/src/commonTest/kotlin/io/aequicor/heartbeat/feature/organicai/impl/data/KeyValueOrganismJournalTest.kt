package io.aequicor.heartbeat.feature.organicai.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.organism
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
    fun `organisms survive a round trip through the journal`() = runTest {
        val organism = organism().copy(attachments = listOf(ResourceRef("attachment:image", "image/png")))
        journal.save(organism)
        assertEquals(setOf(organism.id.value), stores.store.values["index"])
        assertEquals(listOf(organism), KeyValueOrganismJournal(stores, TestDispatchers).load())
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
    fun `ended organisms expire while developing ones are kept`() = runTest {
        journal.save(organism())
        assertTrue(stores.store.retentions.getValue("organism.o1").isPermanent)
        journal.save(organism().copy(status = OrganismStatus.Aborted, version = 2))
        assertFalse(stores.store.retentions.getValue("organism.o1").isPermanent)
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

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class MemoryStores : DataStores {
        val store = MemoryStore()
        override val owner: StorageOwner = StorageOwner.App
        override fun filesDirectory(name: String): String = error("unused")
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = store.also { it.spec = spec }
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
        override suspend fun fire(event: DataEvent) = Unit
    }

    private class MemoryStore : KeyValueStore {
        override lateinit var spec: KeyValueSpec
        val values = mutableMapOf<String, Any>()
        val retentions = mutableMapOf<String, Retention>()

        /** A key whose writes fail, as an interrupted write would. */
        var failing: String? = null

        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = emptyFlow()

        @Suppress("UNCHECKED_CAST") // Test fake: one value type per key.
        override suspend fun <T : Any> get(key: StoreKey<T>): T? = values[key.name] as T?

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
