package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ScopeHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** [DataStores] of one owner: its open stores and databases, closed with [scope] (see [StoreRegistry.attach]). */
internal class OwnerStores(
    override val owner: StorageOwner,
    private val scope: ScopeHandle,
    private val registry: StoreRegistry,
) : DataStores {

    private val closed = CompletableDeferred<Unit>()
    private val openGate = StorageOpenGate(::closeAfterConstructions)
    private val ownerJob = checkNotNull(scope.coroutineScope.coroutineContext[Job])
    val isClosed: Boolean get() = scope.isClosed

    private val keyValues = ConcurrentCache<String, LoggingKeyValueStore>()
    private val databases = ConcurrentCache<String, OpenDatabase>()

    override fun filesDirectory(name: String): String {
        checkOpen()
        return registry.filesDirectory(owner, name)
    }

    override fun keyValue(spec: KeyValueSpec): KeyValueStore = openGate.open {
        checkOpen()
        val store = keyValues.getOrPut(spec.name) { registry.openKeyValue(owner, spec, scope) }
        check(store.spec == spec) { "${owner.label}: kv ${spec.name} is already open as ${store.spec}, not $spec" }
        checkStillOpen()
        store
    }

    // The instance was created by the same spec (checked below), so it is a T.
    @Suppress("UNCHECKED_CAST")
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = openGate.open {
        checkOpen()
        val open = databases.getOrPut(spec.name) { registry.openDatabase(owner, spec, scope) }
        check(open.spec === spec) { "${owner.label}: db ${spec.name} is already open with another DatabaseSpec" }
        checkStillOpen()
        open.db as T
    }

    override suspend fun fire(event: DataEvent) {
        checkOpen()
        registry.fire(owner, event)
    }

    suspend fun purgeEvent(event: String, firedAt: Long) {
        keyValues.values().forEach { it.purgeEvent(event, firedAt) }
        databases.values().forEach { it.retention.purgeEvent(it.db, event, firedAt) }
    }

    /** Revokes new factories immediately; physical close waits for factories and every owner-bound query. */
    fun close() = openGate.close()

    /** Cancelling a waiter does not cancel physical cleanup or weaken the wipe barrier. */
    suspend fun awaitClosed() = closed.await()

    fun onClosed(action: () -> Unit) {
        closed.invokeOnCompletion { failure -> if (failure == null) action() }
    }

    private fun closeAfterConstructions() {
        keyValues.removeAll { true }
        val opened = databases.removeAll { true }
        ownerJob.invokeOnCompletion { registry.closeDatabases(opened, closed) }
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
