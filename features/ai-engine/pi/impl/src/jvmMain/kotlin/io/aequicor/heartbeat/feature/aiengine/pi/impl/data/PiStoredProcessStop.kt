package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Cold stop of exact persisted OS identities. Missing/reused PIDs prove the original process is gone; an unknown
 * start instant of a live process or denied inspection does not. Signals use ProcessHandle, never Process.destroy's
 * blocking pipe cleanup. Descendant observation is a snapshot, not OS containment. Persisting before signalling
 * preserves discovered identities if the application dies between killing a wrapper and observing its children's
 * exit. A process exiting while it is inspected is gone; only a live replacement of its PID voids an inspection.
 */
internal class PiStoredProcessStop(
    private val dispatchers: DispatcherProvider,
    private val lookup: (Long) -> ProcessHandle? = { ProcessHandle.of(it).orElse(null) },
    private val timeoutMillis: Long = STOP_TIMEOUT,
) {
    private val log = Log.tag("PiStoredProcessStop")

    suspend fun stop(
        owner: PiExecutionOwner,
        beginInspection: suspend () -> Boolean,
        record: suspend (PiExecutionOwner) -> PiExecutionOwner?,
    ): Boolean = withContext(dispatchers.io) {
        try {
            // Failures checking already persisted identities remain retryable; no observation has been lost.
            (listOf(owner.root) + owner.observedChildren).forEach { matching(it) }
            if (!beginInspection()) return@withContext false
            val expanded = discover(owner)
            val saved = record(expanded) ?: return@withContext false
            check(saved.launchId == owner.launchId && saved.root == owner.root)
            check(saved.observedChildren.containsAll(expanded.observedChildren))
            withTimeoutOrNull(timeoutMillis) {
                val processes = (saved.observedChildren.asReversed() + saved.root).mapNotNull(::matching)
                processes.forEach { if (it.isAlive) it.destroyForcibly() }
                processes.forEach { it.onExit().await() }
                processes.none { it.isAlive }
            } ?: false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("Process stop failed (${error::class.simpleName.orEmpty()})")) {
                "Pi process termination could not be confirmed"
            }
            false
        }
    }

    /** Read-only exit proof for reopening; a live process of unknown identity or denied inspection is no proof. */
    suspend fun hasExited(owner: PiExecutionOwner): Boolean = withContext(dispatchers.io) {
        try {
            (listOf(owner.root) + owner.observedChildren).none { matching(it) != null }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("Process inspection failed (${error::class.simpleName.orEmpty()})")) {
                "Pi process exit could not be proven"
            }
            false
        }
    }

    private fun discover(owner: PiExecutionOwner): PiExecutionOwner {
        val observed = owner.observedChildren.toMutableSet()
        for (identity in listOf(owner.root) + owner.observedChildren) {
            // beginInspection writes to storage; a handle obtained before that await may now have a reused PID.
            val process = matching(identity) ?: continue
            val descendants = process.descendants().use { stream -> stream.toList().mapNotNull { it.liveIdentity() } }
            // descendants() resolves the current PID tree, unlike ProcessHandle.isAlive's identity check.
            // Refuse the entire observation if another live process took the parent's PID during enumeration.
            check(!isReplaced(identity)) { "Parent changed during descendant inspection" }
            observed += descendants
        }
        return owner.copy(observedChildren = observed.toList())
    }

    private fun matching(identity: PiProcessIdentity): ProcessHandle? {
        val process = lookup(identity.pid) ?: return null
        val current = process.liveIdentity() ?: return null
        return process.takeIf { current.isSame(identity) }
    }

    private fun isReplaced(identity: PiProcessIdentity): Boolean =
        lookup(identity.pid)?.liveIdentity()?.isSame(identity) == false

    /** Null once exited, including an exit while its start instant is read; a live unknown instant is refused. */
    private fun ProcessHandle.liveIdentity(): PiProcessIdentity? {
        if (!isAlive) return null
        val identity = info().startInstant().orElse(null)?.let { PiProcessIdentity(pid(), it.toString()) }
        check(identity != null || !isAlive) { "Live process start instant unavailable" }
        return identity
    }

    private fun PiProcessIdentity.isSame(other: PiProcessIdentity): Boolean =
        pid == other.pid && Instant.parse(startedAt) == Instant.parse(other.startedAt)

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
