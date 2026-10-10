package io.aequicor.heartbeat.core.datastore.impl

import androidx.datastore.core.DataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes a collection's first snapshot with writes to one file, including across owner reopenings.
 *
 * DataStore 1.2.1 may read the previous file after an in-flight write has advanced its version. Its flow then
 * discards the committed snapshot with that same version. Release the lock before emitting the first snapshot:
 * subsequent observations must never prevent writes, including writes made by the collector itself.
 */
internal class SnapshotDataStore<T>(private val delegate: DataStore<T>) : DataStore<T> {
    private val snapshotLock = Mutex()

    override val data: Flow<T> = flow {
        snapshotLock.lock()
        var isInitialSnapshotPending = true
        try {
            delegate.data.collect { value ->
                if (isInitialSnapshotPending) {
                    isInitialSnapshotPending = false
                    snapshotLock.unlock()
                }
                emit(value)
            }
        } finally {
            if (isInitialSnapshotPending) snapshotLock.unlock()
        }
    }

    override suspend fun updateData(transform: suspend (T) -> T): T {
        val caller = currentCoroutineContext()
        return snapshotLock.withLock {
            // DataStore owns accepted IO: cancelling its caller does not necessarily stop the file write.
            // Keep the lock until that IO settles, while preserving cancellation of the caller's transform.
            withContext(NonCancellable) {
                delegate.updateData { value -> withContext(caller) { transform(value) } }
            }
        }
    }
}
