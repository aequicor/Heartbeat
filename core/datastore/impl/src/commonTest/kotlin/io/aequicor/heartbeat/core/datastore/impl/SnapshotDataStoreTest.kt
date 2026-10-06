package io.aequicor.heartbeat.core.datastore.impl

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotDataStoreTest {
    @Test
    fun `observation started during a write sees the commit and later writes`() = runTest {
        val storage = PausedStorage()
        val store = SnapshotDataStore(DataStoreFactory.create(storage = storage, scope = backgroundScope))
        assertEquals(0, store.data.first())
        val write = async { store.updateData { 1 } }
        storage.beforeWrite.await()
        val observed = mutableListOf<Int>()
        backgroundScope.launch { store.data.toList(observed) }
        runCurrent()
        storage.releaseWrite.complete(Unit)
        assertEquals(1, write.await())
        runCurrent()
        assertEquals(1, store.data.first(), "Fresh read sees the durable update")
        assertEquals(listOf(1), observed)
        store.updateData { 2 }
        runCurrent()
        assertEquals(listOf(1, 2), observed, "Continued observation must not hold the write lock")
    }

    @Test
    fun `cancelling an accepted write keeps snapshots waiting until the file is committed`() = runTest {
        val storage = PausedStorage()
        val store = SnapshotDataStore(DataStoreFactory.create(storage = storage, scope = backgroundScope))
        assertEquals(0, store.data.first())
        val write = backgroundScope.async { store.updateData { 1 } }
        storage.beforeWrite.await()
        write.cancel()
        runCurrent()
        val read = backgroundScope.async { store.data.first() }
        runCurrent()
        assertFalse(read.isCompleted, "A cancelled waiter must not expose a partially committed snapshot")
        storage.releaseWrite.complete(Unit)
        runCurrent()
        assertEquals(1, read.await())
        write.join()
        assertTrue(write.isCancelled)
    }

    @Test
    fun `cancelling before the initial snapshot releases subsequent writes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val delegate = object : DataStore<Int> {
            override val data = flow<Int> {
                entered.complete(Unit)
                awaitCancellation()
            }
            override suspend fun updateData(transform: suspend (Int) -> Int): Int = transform(0)
        }
        val store = SnapshotDataStore(delegate)
        val read = backgroundScope.launch { store.data.first() }
        entered.await()
        read.cancelAndJoin()
        val write = backgroundScope.async { store.updateData { 1 } }
        runCurrent()
        assertTrue(write.isCompleted)
        assertEquals(1, write.await())
    }

    @Test
    fun `the initial collector can write without retaining the snapshot lock`() = runTest {
        val storage = PausedStorage().apply { releaseWrite.complete(Unit) }
        val store = SnapshotDataStore(DataStoreFactory.create(storage = storage, scope = backgroundScope))
        val observer = backgroundScope.async {
            store.data.first { value ->
                if (value == 0) store.updateData { 1 }
                value == 1
            }
        }
        runCurrent()
        assertTrue(observer.isCompleted, "Writing inside the first emission must not deadlock")
        assertEquals(1, observer.await())
    }

    @Test
    fun `cancellation inside the transform does not commit and releases the snapshot lock`() = runTest {
        val storage = PausedStorage()
        val store = SnapshotDataStore(DataStoreFactory.create(storage = storage, scope = backgroundScope))
        val entered = CompletableDeferred<Unit>()
        val write = backgroundScope.async {
            store.updateData {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        write.cancelAndJoin()
        assertFalse(storage.beforeWrite.isCompleted, "A cancelled transform must not reach the file write")
        assertEquals(0, store.data.first())
        storage.releaseWrite.complete(Unit)
        assertEquals(1, store.updateData { 1 })
    }

    @Test
    fun `cancellation while waiting for a write never invokes its transform`() = runTest {
        val storage = PausedStorage()
        val store = SnapshotDataStore(DataStoreFactory.create(storage = storage, scope = backgroundScope))
        val first = backgroundScope.async { store.updateData { 1 } }
        storage.beforeWrite.await()
        var wasTransformInvoked = false
        val cancelled = backgroundScope.async {
            store.updateData {
                wasTransformInvoked = true
                2
            }
        }
        runCurrent()
        cancelled.cancelAndJoin()
        storage.releaseWrite.complete(Unit)
        first.await()
        assertFalse(wasTransformInvoked)
        assertEquals(1, store.data.first())
        assertEquals(3, store.updateData { 3 })
    }
}

/** Advances the coordinator version before an explicitly gated file commit, like DataStore storage. */
private class PausedStorage : Storage<Int> {
    val beforeWrite = CompletableDeferred<Unit>()
    val releaseWrite = CompletableDeferred<Unit>()
    private var value = 0
    override fun createConnection(): StorageConnection<Int> = object : StorageConnection<Int> {
        override val coordinator: InterProcessCoordinator = object : InterProcessCoordinator {
            private val mutex = Mutex()
            private var version = 0
            override val updateNotifications = MutableSharedFlow<Unit>()
            override suspend fun <T> lock(block: suspend () -> T): T = mutex.withLock { block() }
            override suspend fun <T> tryLock(block: suspend (Boolean) -> T): T {
                val locked = mutex.tryLock()
                return try {
                    block(locked)
                } finally {
                    if (locked) mutex.unlock()
                }
            }
            override suspend fun getVersion(): Int = version
            override suspend fun incrementAndGetVersion(): Int = ++version
        }
        private val reader = object : WriteScope<Int> {
            override suspend fun readData(): Int = value
            override suspend fun writeData(value: Int) {
                beforeWrite.complete(Unit)
                releaseWrite.await()
                this@PausedStorage.value = value
            }
            override fun close() = Unit
        }
        override suspend fun <R> readScope(block: suspend ReadScope<Int>.(Boolean) -> R): R = block(reader, true)
        override suspend fun writeScope(block: suspend WriteScope<Int>.() -> Unit) = block(reader)
        override fun close() = Unit
    }
}
