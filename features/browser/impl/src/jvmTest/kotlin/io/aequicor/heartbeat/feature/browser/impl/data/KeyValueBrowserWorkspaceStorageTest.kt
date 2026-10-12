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
import io.aequicor.heartbeat.feature.browser.impl.domain.MAX_BROWSER_TABS
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserTab
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserWorkspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KeyValueBrowserWorkspaceStorageTest {
    @Test
    fun `metadata round trips through serialization and is isolated by profile`() = runTest {
        val first = MemoryStores("one")
        val second = MemoryStores("two")
        val saved = SavedBrowserWorkspace(
            listOf(SavedBrowserTab("tab", "https://example.com/a?b=c", "Документ", "session")),
            "tab",
        )
        KeyValueBrowserWorkspaceStorage(first).save(saved)
        assertEquals(saved, KeyValueBrowserWorkspaceStorage(first).load())
        assertEquals(SavedBrowserWorkspace(), KeyValueBrowserWorkspaceStorage(second).load())
        assertFalse(first.store.spec.areValuesLogged)
        assertTrue(first.store.retention.isPermanent)
    }

    @Test
    fun `repeated projections do not write and failed writes remain retryable`() = runTest {
        val stores = MemoryStores("one")
        val storage = KeyValueBrowserWorkspaceStorage(stores)
        val saved = SavedBrowserWorkspace(listOf(SavedBrowserTab("tab")), "tab")
        storage.save(saved)
        storage.save(saved)
        assertEquals(1, stores.store.writes)
        stores.store.onWrite = { error("Storage unavailable") }
        assertFailsWith<IllegalStateException> { storage.save(SavedBrowserWorkspace()) }
        stores.store.onWrite = {}
        storage.save(SavedBrowserWorkspace())
        assertEquals(SavedBrowserWorkspace(), KeyValueBrowserWorkspaceStorage(stores).load())
        assertEquals(2, stores.store.writes)
    }

    @Test
    fun `clear cannot be overtaken by an older suspended write`() = runTest {
        val stores = MemoryStores("one")
        val storage = KeyValueBrowserWorkspaceStorage(stores)
        val gate = CompletableDeferred<Unit>()
        stores.store.onWrite = { gate.await() }
        val old = async { storage.save(SavedBrowserWorkspace(listOf(SavedBrowserTab("old")), "old")) }
        runCurrent()
        val clear = async { storage.save(SavedBrowserWorkspace()) }
        runCurrent()
        assertFalse(clear.isCompleted)
        gate.complete(Unit)
        old.await()
        clear.await()
        assertEquals(SavedBrowserWorkspace(), KeyValueBrowserWorkspaceStorage(stores).load())
    }

    @Test
    fun `restoration filters duplicate identities unsupported URLs and excessive tabs`() = runTest {
        val stores = MemoryStores("one")
        KeyValueBrowserWorkspaceStorage(stores).save(
            SavedBrowserWorkspace(
                listOf(
                    SavedBrowserTab(""),
                    SavedBrowserTab("bad", "javascript:alert(1)"),
                    SavedBrowserTab("local", "file:///private/data"),
                    SavedBrowserTab("one", "https://example.com/"),
                    SavedBrowserTab("one", "https://other.example/"),
                ) + (0..MAX_BROWSER_TABS).map { SavedBrowserTab("tab-$it") },
                "missing",
            ),
        )
        val restored = KeyValueBrowserWorkspaceStorage(stores).load()
        assertEquals(MAX_BROWSER_TABS, restored.tabs.size)
        assertEquals("one", restored.selectedTabId)
        assertEquals("https://example.com/", restored.tabs.first().url)
        assertEquals(restored.tabs.size, restored.tabs.map { it.id }.distinct().size)
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
