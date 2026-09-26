package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log

/** [DataStores] of one owner: its open stores and databases, closed with [scope] (see [StoreRegistry.attach]). */
internal class OwnerStores(
    override val owner: StorageOwner,
    private val scope: ScopeHandle,
    private val registry: StoreRegistry,
) : DataStores {

    private val keyValues = ConcurrentCache<String, LoggingKeyValueStore>()
    private val databases = ConcurrentCache<String, OpenDatabase>()

    override fun keyValue(spec: KeyValueSpec): KeyValueStore {
        checkOpen()
        val store = keyValues.getOrPut(spec.name) { registry.openKeyValue(owner, spec, scope) }
        check(store.spec == spec) { "${owner.label}: kv ${spec.name} is already open as ${store.spec}, not $spec" }
        checkStillOpen()
        return store
    }

    // The instance was created by the same spec (checked below), so it is a T.
    @Suppress("UNCHECKED_CAST")
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T {
        checkOpen()
        val open = databases.getOrPut(spec.name) { registry.openDatabase(owner, spec, scope) }
        check(open.spec === spec) { "${owner.label}: db ${spec.name} is already open with another DatabaseSpec" }
        checkStillOpen()
        return open.db as T
    }

    override suspend fun fire(event: DataEvent) {
        registry.fire(owner, event)
    }

    suspend fun purgeEvent(event: String, firedAt: Long) {
        keyValues.values().forEach { it.purgeEvent(event, firedAt) }
        databases.values().forEach { it.retention.purgeEvent(it.db, event, firedAt) }
    }

    /** Closes the databases; the Preferences files stay with the registry. Called when [scope] closes. */
    fun close() {
        keyValues.removeAll { true }
        databases.removeAll { true }.forEach { open ->
            open.db.close()
            Log.tag(DB_LOG_TAG).i { "${open.label}: closed" }
        }
    }

    private fun checkOpen() {
        check(!scope.isClosed) { "storages of ${owner.label} are closed with scope ${scope.name}" }
    }

    /** The scope may have closed while a storage was being opened on another thread: close what was left behind. */
    private fun checkStillOpen() {
        if (scope.isClosed) close()
        checkOpen()
    }
}
