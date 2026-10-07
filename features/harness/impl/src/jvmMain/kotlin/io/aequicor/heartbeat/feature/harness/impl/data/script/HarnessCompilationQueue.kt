package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One compiler per profile; agent requests precede queued background requests without preempting a compiler. */
internal class HarnessCompilationQueue {
    private val mutex = Mutex()
    private var isBusy = false
    private val agent = ArrayDeque<CompletableDeferred<Unit>>()
    private val background = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> run(isAgent: Boolean, block: suspend () -> T): T {
        val permit = CompletableDeferred<Unit>()
        mutex.withLock {
            if (!isBusy) {
                isBusy = true
                permit.complete(Unit)
            } else if (isAgent) {
                agent.addLast(permit)
            } else {
                background.addLast(permit)
            }
        }
        try {
            permit.await()
            return block()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val isQueued = agent.remove(permit) || background.remove(permit)
                    if (!isQueued) releaseNext()
                }
            }
        }
    }

    private fun releaseNext() {
        val next = agent.removeFirstOrNull() ?: background.removeFirstOrNull()
        if (next == null) isBusy = false else next.complete(Unit)
    }
}

/** Separates the compiler's resource ownership from a detachable waiter. */
internal class HarnessCompilationTicket {
    private var value: HarnessCompilationResult? = null
    private var isDropped = false
    private var isClaimed = false

    @Synchronized
    fun publish(result: HarnessCompilationResult) {
        if (isDropped) result.releaseCode() else value = result
    }

    @Synchronized
    fun claim(): HarnessCompilationResult {
        check(!isDropped && !isClaimed)
        isClaimed = true
        return checkNotNull(value).also { value = null }
    }

    @Synchronized
    fun drop() {
        if (!isClaimed) {
            isDropped = true
            value?.releaseCode()
            value = null
        }
    }
}

private fun HarnessCompilationResult.releaseCode() {
    (this as? HarnessCompilationResult.Success)?.code?.close()
}
