package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orders compiler admission and cache deletion for one immutable item identity. A detached compiler may outlive
 * its waiter; host.removeCached invalidates that already admitted work. New compiler admission waits until the
 * removal finishes, so a delayed cleanup cannot invalidate a future generation's newly submitted compiler.
 */
internal class HarnessRuntimeCache(private val host: HarnessScriptHost) {
    private val mutex = Mutex()
    private val items = mutableMapOf<Pair<HarnessId, ItemId>, Mutex>()

    suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult =
        itemMutex(request.harness, request.item).withLock { host.compile(request) }

    /** The predicate runs inside the item lock, before IO; it must not acquire another item lock. */
    suspend fun remove(harness: HarnessId, item: ItemId, mayRemove: suspend () -> Boolean): HarnessCacheRemoval {
        val attempt = captureHarnessFailure {
            itemMutex(harness, item).withLock {
                if (mayRemove()) {
                    host.removeCached(harness, item)
                    HarnessCacheRemoval.Removed
                } else {
                    HarnessCacheRemoval.Skipped
                }
            }
        }
        return when (attempt) {
            is HarnessAttempt.Success -> attempt.value
            is HarnessAttempt.Failure -> HarnessCacheRemoval.Failed
        }
    }

    private suspend fun itemMutex(harness: HarnessId, item: ItemId): Mutex = mutex.withLock {
        items.getOrPut(harness to item) { Mutex() }
    }
}

internal enum class HarnessCacheRemoval { Removed, Skipped, Failed }
