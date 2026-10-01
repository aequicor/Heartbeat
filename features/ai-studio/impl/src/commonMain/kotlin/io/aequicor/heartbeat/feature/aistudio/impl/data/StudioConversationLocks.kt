package io.aequicor.heartbeat.feature.aistudio.impl.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes native handle creation and release; entries disappear after their final waiter leaves. */
internal class StudioConversationLocks(private val registryLock: Mutex) {
    private val opening = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(id: String, block: suspend () -> T): T {
        val entry = registryLock.withLock { opening.getOrPut(id) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                registryLock.withLock {
                    entry.users--
                    if (entry.users == 0 && opening[id] === entry) opening.remove(id)
                }
            }
        }
    }

    private class Entry {
        val mutex = Mutex()
        var users = 0
    }
}
