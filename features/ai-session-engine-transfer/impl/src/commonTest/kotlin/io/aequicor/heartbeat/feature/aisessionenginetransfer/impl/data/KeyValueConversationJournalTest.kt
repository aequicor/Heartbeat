package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.ConversationId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.ConversationSegment
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.LogicalConversation
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class KeyValueConversationJournalTest {
    private val stores = FakeDataStores()
    private val journal = KeyValueConversationJournal(stores)
    private val conversation =
        LogicalConversation(ConversationId("c1"), listOf(ConversationSegment(SourceRef, Instant.fromEpochSeconds(1))))

    @Test
    fun `conversations are stored per id in the profile store`() = runTest {
        assertNull(journal.get(conversation.id))
        journal.put(conversation)
        journal.put(conversation.copy(id = ConversationId("c2")))

        assertEquals(conversation, journal.get(conversation.id))
        assertEquals(setOf("c1", "c2"), stores.store.values.keys)
        assertEquals("ai_session_transfer_conversations", stores.store.spec.name)

        journal.remove(conversation.id)
        assertNull(journal.get(conversation.id))
    }

    private class FakeDataStores : DataStores {
        override fun filesDirectory(name: String): String = error("File storage is not used by this fake")
        val store = FakeKeyValueStore()
        override val owner: StorageOwner = StorageOwner.Profile(ProfileId("profile"))

        override fun keyValue(spec: KeyValueSpec): KeyValueStore = store.also { it.spec = spec }

        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")

        override suspend fun fire(event: DataEvent) = Unit
    }

    private class FakeKeyValueStore : KeyValueStore {
        override lateinit var spec: KeyValueSpec
        val values = mutableMapOf<String, Any>()

        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = emptyFlow()

        @Suppress("UNCHECKED_CAST") // test fake: one value type per key
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
