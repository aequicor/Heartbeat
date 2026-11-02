package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes host retirement with admission, never with native RPC. Tickets cover the entire handoff, including
 * late-open publication/cleanup. A caller cannot interpret an empty ticket set as native terminal evidence.
 * Lock order is admissions then canonical host storage; providers and persist callbacks must not reenter it.
 */
class SessionExecutionAdmissions(private val authorities: Lazy<Set<SessionExecutionAuthority>>) {
    private val log = Log.tag("SessionExecutionAdmissions")
    private val mutex = Mutex()
    private val fenced = mutableSetOf<SessionExecutionInstance>()
    private val durable = mutableSetOf<SessionExecutionInstance>()
    private val tickets = mutableMapOf<SessionExecutionInstance, MutableSet<CompletableDeferred<Unit>>>()

    /** Runs one admitted handoff; its ticket is registered before invoking any native code. */
    suspend fun <T> admitted(
        binding: SessionExecutionBinding,
        entry: Pair<SessionRef, RequestId>? = null,
        block: suspend () -> T,
    ): T {
        val ticket = mutex.withLock {
            requireAllowed(binding)
            if (entry != null) {
                check(authority(binding).enterPrompt(binding, entry.first, entry.second)) {
                    "Native request entry is unavailable or already consumed"
                }
            }
            CompletableDeferred<Unit>().also { tickets.getOrPut(binding.instance) { mutableSetOf() }.add(it) }
        }
        var isReturned = false
        try {
            val result = block()
            isReturned = true
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            isReturned = true
            throw e
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    // Cancellation does not prove the remote operation returned. Keep an unresolved ticket;
                    // a timeout may report Unknown, but cannot turn interruption into a drained handoff.
                    if (isReturned) {
                        tickets[binding.instance]?.let {
                            it.remove(ticket)
                            if (it.isEmpty()) tickets.remove(binding.instance)
                        }
                        ticket.complete(Unit)
                    }
                }
            }
        }
    }

    /** Rechecks before publishing a late open; rejection must release the opened native handle. */
    suspend fun check(binding: SessionExecutionBinding): Unit = mutex.withLock { requireAllowed(binding) }

    /** Closes admission before canonical persistence; failed persistence leaves the volatile fence closed. */
    suspend fun fence(binding: SessionExecutionBinding, persist: suspend () -> Unit): Unit = mutex.withLock {
        fenced += binding.instance
        // Failure deliberately keeps the volatile barrier closed, but cannot claim durable retirement.
        persist()
        durable += binding.instance
        log.i { "execution instance admission durably fenced" }
    }

    /** Drains transient handoffs only; the access layer must additionally check durable opening reservations. */
    suspend fun awaitAdmissions(binding: SessionExecutionBinding) {
        val pending = mutex.withLock {
            check(binding.instance in durable) { "Durable retirement must precede draining admissions" }
            tickets[binding.instance].orEmpty().toList()
        }
        pending.forEach { it.await() }
    }

    /** Validates the immutable caller payload without consuming its later native handoff. */
    suspend fun checkPrompt(
        binding: SessionExecutionBinding,
        session: SessionRef,
        target: EngineTarget,
        request: PromptRequest,
    ) = mutex.withLock {
        requireAllowed(binding)
        check(
            authority(binding).permitsPrompt(binding, session, target, request),
        ) { "Executor prompt is not authorized" }
    }

    private fun authority(binding: SessionExecutionBinding): SessionExecutionAuthority =
        checkNotNull(authorities.value.singleOrNull { it.provider == binding.instance.provider }) {
            "Execution authority is unavailable or ambiguous"
        }

    private suspend fun requireAllowed(binding: SessionExecutionBinding) {
        check(binding.instance !in fenced) { "Execution instance is retired" }
        check(authority(binding).permits(binding)) { "Execution authority is unavailable or denied" }
    }
}
