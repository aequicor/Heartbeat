package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Cold stop of exact persisted OS identities. Missing/reused PIDs prove the original process is gone; an unknown
 * start instant or denied inspection does not. Signals use ProcessHandle, never Process.destroy's blocking pipe
 * cleanup. Descendant observation is a snapshot, not OS containment. Persisting before signalling preserves
 * discovered identities if the application dies between killing a wrapper and observing its children's exit.
 */
internal class CodexProcessStop(
    private val dispatchers: DispatcherProvider,
    private val lookup: (Long) -> ProcessHandle? = { ProcessHandle.of(it).orElse(null) },
    private val timeoutMillis: Long = STOP_TIMEOUT,
) {
    private val log = Log.tag("CodexProcessStop")

    suspend fun stop(
        owner: CodexExecutionOwner,
        beginInspection: suspend () -> Boolean,
        record: suspend (CodexExecutionOwner) -> CodexExecutionOwner?,
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
                "Codex process termination could not be confirmed"
            }
            false
        }
    }

    private fun discover(owner: CodexExecutionOwner): CodexExecutionOwner {
        val observed = owner.observedChildren.toMutableSet()
        for (identity in listOf(owner.root) + owner.observedChildren) {
            // beginInspection writes to storage; a handle obtained before that await may now have a reused PID.
            val process = matching(identity) ?: continue
            val descendants = process.descendants().use { stream ->
                stream.map { child -> if (child.isAlive) checkNotNull(child.identity()) else null }.toList()
            }
            // descendants() resolves the current PID tree, unlike ProcessHandle.isAlive's identity check.
            // Refuse the entire observation if the parent changed while enumeration was in flight.
            checkNotNull(matching(identity)) { "Parent changed during descendant inspection" }
            observed += descendants.filterNotNull()
        }
        return owner.copy(observedChildren = observed.toList())
    }

    private fun matching(identity: CodexProcessIdentity): ProcessHandle? {
        val process = lookup(identity.pid) ?: return null
        if (!process.isAlive) return null
        val current = checkNotNull(process.identity()) { "Live process start instant unavailable" }
        return process.takeIf {
            current.pid == identity.pid && Instant.parse(current.startedAt) == Instant.parse(identity.startedAt)
        }
    }

    private fun ProcessHandle.identity(): CodexProcessIdentity? =
        info().startInstant().orElse(null)?.let { CodexProcessIdentity(pid(), it.toString()) }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
