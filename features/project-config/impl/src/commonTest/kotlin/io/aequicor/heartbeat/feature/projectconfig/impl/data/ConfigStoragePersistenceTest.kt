package io.aequicor.heartbeat.feature.projectconfig.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.StoreValueType
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigStorage
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigStorageSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigStoragePersistenceTest {
    @Test
    fun `default and overrides are decoded by a new repository instance`() = runTest {
        val stores = StorageTestStores()
        val storage = KeyValueConfigBindings(stores)
        val project = WorkspaceRef("project")
        assertEquals(ConfigStorageSettings(), storage.loadSettings())
        val chosen = ConfigStorageSettings(ConfigStorage.App, mapOf(project.value to ConfigStorage.Project))
        storage.saveSettings(chosen)
        assertEquals(chosen, KeyValueConfigBindings(stores).loadSettings())
        assertTrue(stores.stores.values.single().values.value["settings"].orEmpty().contains("App"))
        assertEquals(KeyValueReadPolicy.Strict, stores.stores.keys.single().readPolicy)
    }

    @Test
    fun `a failed preference write preserves the app choice and keeps project IO disabled`() = runTest {
        val stores = StorageTestStores()
        val storage = KeyValueConfigBindings(stores)
        val policy = ConfigStoragePolicy(storage)
        val app = ConfigStorageSettings(ConfigStorage.App)
        policy.save(app)
        stores.stores.values.single().failWrites = true
        assertFailsWith<IllegalStateException> { policy.save(ConfigStorageSettings()) }
        assertEquals(app, KeyValueConfigBindings(stores).loadSettings())
        assertEquals(null, policy.access(WorkspaceRef("project")) { error("Project IO must stay disabled") })
    }

    @Test
    fun `unreadable preferences never fall back to project access`() = runTest {
        val stores = StorageTestStores()
        val storage = KeyValueConfigBindings(stores)
        storage.saveSettings(ConfigStorageSettings(ConfigStorage.App))
        stores.stores.values.single().values.value = mapOf("settings" to "broken")
        assertFailsWith<IllegalArgumentException> {
            ConfigStoragePolicy(storage).access(WorkspaceRef("project")) { error("Project IO must stay disabled") }
        }
    }
}

private class StorageTestStores : DataStores {
    val stores = mutableMapOf<KeyValueSpec, StorageTestStore>()
    override val owner = StorageOwner.App
    override fun filesDirectory(name: String): String = error("unused")
    override fun keyValue(spec: KeyValueSpec): StorageTestStore = stores.getOrPut(spec) { StorageTestStore(spec) }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
    override suspend fun fire(event: DataEvent) = Unit
}

private class StorageTestStore(override val spec: KeyValueSpec) : KeyValueStore {
    val values = MutableStateFlow(emptyMap<String, String>())
    var failWrites = false
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { decode(key, it[key.name]) }
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = decode(key, values.value[key.name])
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        check(!failWrites) { "disk unavailable" }
        val json = key.type as? StoreValueType.Json<T>
        values.value += key.name to if (json == null) value as String else Json.encodeToString(json.serializer, value)
    }
    override suspend fun remove(key: StoreKey<*>) {
        values.value -= key.name
    }
    override suspend fun clear() {
        values.value = emptyMap()
    }

    @Suppress("UNCHECKED_CAST") // The fake serializes JSON and supports the string keys used by the journal.
    private fun <T : Any> decode(key: StoreKey<T>, value: String?): T? {
        if (value == null) return null
        val json = key.type as? StoreValueType.Json<T>
        return if (json == null) value as T else Json.decodeFromString(json.serializer, value)
    }
}
