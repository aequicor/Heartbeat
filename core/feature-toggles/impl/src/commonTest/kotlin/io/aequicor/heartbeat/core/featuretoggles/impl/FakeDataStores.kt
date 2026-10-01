package io.aequicor.heartbeat.core.featuretoggles.impl

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory app [DataStores]: key-value only. */
internal class FakeDataStores : DataStores {
    override fun filesDirectory(name: String): String = error("File storage is not used by this fake")
    private val stores = mutableMapOf<String, FakeKeyValueStore>()

    override val owner: StorageOwner = StorageOwner.App

    override fun keyValue(spec: KeyValueSpec): KeyValueStore = stores.getOrPut(spec.name) { FakeKeyValueStore(spec) }

    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("no databases in this test")

    override suspend fun fire(event: DataEvent) = Unit
}

/** In-memory [KeyValueStore]; like the real one, a value of another type reads as absent. */
internal class FakeKeyValueStore(override val spec: KeyValueSpec) : KeyValueStore {
    val values = MutableStateFlow(emptyMap<String, Any>())

    /** When set, every read fails with it, like a broken file. */
    var readFailure: Exception? = null

    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { it.read(key) }.distinctUntilChanged()

    override suspend fun <T : Any> get(key: StoreKey<T>): T? = values.value.read(key)

    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        values.update { it + (key.name to value) }
    }

    override suspend fun remove(key: StoreKey<*>) {
        values.update { it - key.name }
    }

    override suspend fun clear() {
        values.value = emptyMap()
    }

    @Suppress("UNCHECKED_CAST") // test fake: the type is checked against StoreValueType below
    private fun <T : Any> Map<String, Any>.read(key: StoreKey<T>): T? {
        readFailure?.let { throw it }
        val raw = this[key.name] ?: return null
        val matches = when (key.type) {
            StoreValueType.Flag -> raw is Boolean
            StoreValueType.Text -> raw is String
            else -> error("unsupported in this test: ${key.type}")
        }
        return if (matches) raw as T else null
    }
}
