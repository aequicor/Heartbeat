package io.aequicor.heartbeat.feature.browser.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.StoreValueType
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KeyValueBrowserZoomStorageTest {
    @Test
    fun `site zoom survives repository recreation and remains isolated by profile`() = runTest {
        val first = MemoryStores("one")
        val second = MemoryStores("two")
        val storage = KeyValueBrowserZoomStorage(first)
        storage.save("example.com", 125)
        storage.save("other.example.com", 90)
        storage.save("third.example.com", 150)
        assertEquals(
            mapOf("example.com" to 125, "other.example.com" to 90, "third.example.com" to 150),
            KeyValueBrowserZoomStorage(first).load(),
        )
        assertEquals(emptyMap(), KeyValueBrowserZoomStorage(second).load())
        assertFalse(first.store.spec.areValuesLogged)
        storage.save("example.com", 100)
        assertFalse("example.com" in storage.load())
        storage.clear()
        assertEquals(emptyMap(), KeyValueBrowserZoomStorage(first).load())
    }

    @Test
    fun `failed zoom persistence does not replace the previous value`() = runTest {
        val stores = MemoryStores("one")
        val storage = KeyValueBrowserZoomStorage(stores)
        storage.save("example.com", 125)
        stores.store.onWrite = { error("Disk unavailable") }
        assertFailsWith<IllegalStateException> { storage.save("example.com", 150) }
        assertEquals(125, storage.load()["example.com"])
        assertFailsWith<IllegalArgumentException> { storage.save("example.com", 501) }
    }

    private class MemoryStores(id: String) : DataStores {
        override val owner = StorageOwner.Profile(ProfileId(id))
        val store = JsonStore()
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = store.also { it.spec = spec }
        override fun filesDirectory(name: String): String = error("unused")
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
        override suspend fun fire(event: DataEvent) = Unit
    }

    /** Stores actual serialized bytes, so reopening the repository cannot reuse the saved object instance. */
    private class JsonStore : KeyValueStore {
        override var spec = KeyValueSpec("test")
        var retention = Retention.Permanent
        var writes = 0
        var onWrite: suspend () -> Unit = {}
        private val values = mutableMapOf<String, String>()
        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = emptyFlow()
        override suspend fun <T : Any> get(key: StoreKey<T>): T? {
            val serializer = (key.type as StoreValueType.Json<T>).serializer
            return values[key.name]?.let { Json.decodeFromString(serializer, it) }
        }
        override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
            onWrite()
            values[key.name] = Json.encodeToString((key.type as StoreValueType.Json<T>).serializer, value)
            this.retention = retention
            writes++
        }
        override suspend fun remove(key: StoreKey<*>) {
            values.remove(key.name)
        }
        override suspend fun clear() = values.clear()
    }
}
