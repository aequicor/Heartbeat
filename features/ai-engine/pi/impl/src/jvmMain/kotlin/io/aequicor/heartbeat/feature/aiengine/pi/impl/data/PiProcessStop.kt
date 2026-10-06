package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Distinguishes requesting termination from observing exit of the owned process and captured descendants.
 * Captured handles remain across retries; a denied kill or timed-out wait never becomes proof of termination.
 * Descendants are a snapshot, not OS process-tree containment: detached, previously unobserved children are not
 * covered by this barrier. Cold recovery additionally needs durable ownership of the original process.
 */
internal class PiProcessStop(
    private val process: Process,
    private val dispatchers: DispatcherProvider,
    private val timeoutMillis: Long = STOP_TIMEOUT,
) {
    private val log = Log.tag("PiProcessStop")
    private val lock = Any()
    private val children = mutableSetOf<ProcessHandle>()
    private var isCaptured = false

    /** Requests termination, retaining evidence for a later suspending wait. Safe to call from close hooks. */
    fun request() = synchronized(lock) {
        isCaptured = attempt { children += process.descendants().use { it.toList() } }
        children.toList().asReversed().forEach { child -> attempt { if (child.isAlive) child.destroyForcibly() } }
        // A denied child inspection/kill must not prevent requesting termination of our known root.
        attempt {
            // Process.destroy also closes Java pipes synchronously. ProcessHandle sends only the OS signal.
            val root = process.toHandle()
            if (root.isAlive) root.destroyForcibly()
        }
        Unit
    }

    private fun attempt(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Process stop failed (${error::class.simpleName.orEmpty()})")) {
            "Pi process termination could not be confirmed"
        }
        false
    }

    /** True only after all observed exits; cancellation propagates and retains the handles for a retry. */
    suspend fun awaitStopped(): Boolean = withContext(dispatchers.io) {
        request()
        val observed = synchronized(lock) { if (isCaptured) children.toList() else null } ?: return@withContext false
        try {
            withTimeoutOrNull(timeoutMillis) {
                process.onExit().await()
                observed.forEach { it.onExit().await() }
                // Another waiter may have discovered children while this one waited for the root.
                synchronized(lock) { isCaptured && !process.isAlive && children.none { it.isAlive } }
            } ?: false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("Process exit unavailable (${error::class.simpleName.orEmpty()})")) {
                "Pi process exit observation failed"
            }
            false
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
