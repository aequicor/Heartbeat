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
 * Enumeration resolves the root's PID, which the OS may reuse after exit: only a listing taken while the root
 * stayed alive is adopted, and after its exit only handles captured earlier are signalled.
 */
internal class PiProcessStop(
    private val process: Process,
    private val dispatchers: DispatcherProvider,
    private val timeoutMillis: Long = STOP_TIMEOUT,
) {
    private val log = Log.tag("PiProcessStop")
    private val lock = Any()
    private val children = mutableSetOf<ProcessHandle>()

    // Whether the latest listing of the live root succeeded; a root that exited unobserved has nothing to list.
    private var isCaptured = true

    /** Requests termination, retaining evidence for a later suspending wait. Safe to call from close hooks. */
    fun request() = synchronized(lock) {
        if (process.isAlive) capture()
        children.toList().asReversed().forEach { child -> attempt { if (child.isAlive) child.destroyForcibly() } }
        // A denied child inspection/kill must not prevent requesting termination of our known root.
        attempt {
            // Process.destroy also closes Java pipes synchronously. ProcessHandle sends only the OS signal.
            val root = process.toHandle()
            if (root.isAlive) root.destroyForcibly()
        }
        Unit
    }

    /**
     * Called under [lock] while the root was alive; an exit during the listing voids it. A live descendant without
     * a start instant cannot be told apart from a later reuse of its PID: it is never signalled, and the capture
     * stays incomplete so no exit is confirmed without it.
     */
    private fun capture() {
        var identified: List<ProcessHandle> = emptyList()
        var unidentified: List<ProcessHandle> = emptyList()
        val isListed = attempt {
            val listed = process.descendants().use { it.toList() }
            listed.partition { it.info().startInstant().isPresent }.let { (known, unknown) ->
                identified = known
                unidentified = unknown
            }
        }
        isCaptured = isListed
        if (process.isAlive) {
            children += identified
            isCaptured = isListed && unidentified.none { it.isAlive }
        } else if (identified.isNotEmpty() || unidentified.isNotEmpty()) {
            log.d { "Pi process exited during descendant inspection; its listing was discarded" }
        }
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
