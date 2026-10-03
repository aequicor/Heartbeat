package io.aequicor.heartbeat.feature.agentlearning.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KeyValueLearningStorageTest {
    private val stores = MemoryStores()
    private val storage = KeyValueLearningStorage(stores)
    private val lesson = LearnedInstruction(InstructionId("1"), InstructionKind.General, PROJECT, "UTF-8", "Use UTF-8")

    @Test
    fun `registry and approval survive a round trip and default to asking`() = runTest {
        assertEquals(LearningApproval.Ask, storage.load().approval)
        storage.saveInstructions(listOf(lesson))
        storage.saveApproval(LearningApproval.AcceptAll)
        val loaded = storage.load()
        assertEquals(listOf(lesson), loaded.instructions)
        assertEquals(LearningApproval.AcceptAll, loaded.approval)
    }

    @Test
    fun `an unreadable registry fails the load instead of reading as empty`() = runTest {
        stores.store.values["instructions"] = """[{"id":{"value":"1"},"kind":"general","title":"","content":"x"}]"""
        assertFails { storage.load() }
        stores.store.values["instructions"] = "not json"
        assertFails { storage.load() }
    }

    @Test
    fun `a load failure never carries the stored texts`() = runTest {
        val unreadable = listOf(
            """[{"id":{"value":"1"},"kind":"general","title":"Deploy to prod-db-7","content": broken}]""",
            """[{"id":{"value":"1"},"kind":"Deploy to prod-db-7","title":"T","content":"x"}]""",
        )
        for (raw in unreadable) {
            stores.store.values["instructions"] = raw
            val failure = assertFailsWith<IllegalStateException> { storage.load() }
            assertFalse("prod-db-7" in failure.toString(), failure.toString())
            assertNull(failure.cause)
        }
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

        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = emptyFlow()

        @Suppress("UNCHECKED_CAST") // Test fake: one value type per key.
        override suspend fun <T : Any> get(key: StoreKey<T>): T? = values[key.name] as T?

        override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
            values[key.name] = value
        }

        override suspend fun remove(key: StoreKey<*>) {
            values.remove(key.name)
        }

        override suspend fun clear() = values.clear()
    }
}
