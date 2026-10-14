package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Revocable authority of a retained surface; cancellation never replaces its document registration. */
internal class SurfaceExecution(val session: SurfaceSession, parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    val isActive: Boolean get() = job.isActive && session.host.isActive()

    fun cancel() = scope.cancel()

    /** Both the caller and a host rebind cancel a call; uncooperative late results are discarded. */
    suspend fun <T> execute(block: suspend () -> T): T? {
        if (!isActive) return null
        val deferred = scope.async {
            if (!isActive) return@async null
            val result = block()
            currentCoroutineContext().ensureActive()
            result.takeIf { isActive }
        }
        return try {
            // join waits for completion without transferring the child's cancellation to the retained observer.
            // It still propagates cancellation of the caller; an independently cancelled live call fails at await.
            deferred.join()
            currentCoroutineContext().ensureActive()
            if (isActive) deferred.await().takeIf { isActive } else null
        } finally {
            deferred.cancel()
        }
    }
}
