package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionOperation
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Restores immutable authority even when callers omit host metadata. Unknown native creates quarantine unbound
 * access to their engine until the returned ref is durably bound; absence of a ref never proves no native session.
 * Lock order: instance admission, metadata, storage. Providers and ticket waits never run under the metadata mutex.
 */
class SessionExecutionAccess(
    private val bindings: SessionExecutionBindings,
    val admissions: SessionExecutionAdmissions,
) : SessionExecutionControl {
    private val metadata = Mutex()
    private val unbound = mutableMapOf<EngineId, MutableSet<CompletableDeferred<Unit>>>()
    private val quarantine = mutableMapOf<SessionExecutionOpening, SessionRef>()

    /** Rejects owner changes; resume can only restore an authority established by creation. */
    suspend fun resolve(
        ref: SessionRef,
        requested: SessionExecutionBinding?,
        logical: LogicalSessionContext?,
    ): SessionExecutionBinding? {
        val stored = metadata.withLock { bindings.find(ref) }
        check(stored == null || requested == null || stored == requested) { "Native execution authority cannot change" }
        check(requested == null || stored != null) { "Resume cannot establish new native execution authority" }
        validate(stored, logical)
        check(ref, stored)
        return stored
    }

    /** Checks caller consistency before native creation. */
    fun validate(binding: SessionExecutionBinding?, logical: LogicalSessionContext?) {
        check(binding == null || logical == null || binding.logicalSession == logical) {
            "Logical identity does not match execution authority"
        }
    }

    /** Reserves authority before RPC, then drains older unbound handoffs outside all locks. */
    suspend fun <T> creating(
        engine: EngineId,
        token: String,
        binding: SessionExecutionBinding?,
        block: suspend (SessionExecutionOpening?) -> T,
    ): T = if (binding == null) {
        unbound(engine) { block(null) }
    } else {
        admissions.admitted(binding) {
            val opening = SessionExecutionOpening(token, engine, binding)
            val older = metadata.withLock {
                bindings.reserve(opening)
                unbound[engine].orEmpty().toList()
            }
            older.forEach { it.await() }
            block(opening)
        }
    }

    /** Atomically replaces the durable reservation; failed storage retains both reservation and runtime ref. */
    suspend fun record(ref: SessionRef, opening: SessionExecutionOpening?) {
        if (opening == null) return
        metadata.withLock {
            quarantine[opening] = ref
            bindings.complete(opening, ref)
            quarantine.remove(opening)
        }
    }

    /** Retries known late refs without clearing unresolved openings whose native ref was never acknowledged. */
    suspend fun retryBindings() = metadata.withLock {
        quarantine.toMap().forEach { (opening, ref) ->
            bindings.complete(opening, ref)
            quarantine.remove(opening)
        }
    }

    /** A cached unbound handle participates in the same barrier as creation's authority claim. */
    suspend fun <T> admitted(
        ref: SessionRef,
        binding: SessionExecutionBinding?,
        entry: RequestId? = null,
        operation: SessionExecutionOperation = SessionExecutionOperation.Open,
        permission: Pair<PermissionRelay, PermissionDecision>? = null,
        block: suspend () -> T,
    ): T = if (binding == null) {
        unbound(ref.engine) {
            check(ref, null)
            block()
        }
    } else {
        if (permission != null) check(permission.first.session == ref && permission.first.binding == binding)
        admissions.admitted(binding, entry?.let { ref to it }, operation, permission) {
            check(ref, binding)
            block()
        }
    }

    /** Original caller payload, checked before trusted host hooks prepare the native request. */
    suspend fun checkPrompt(
        ref: SessionRef,
        binding: SessionExecutionBinding?,
        target: EngineTarget,
        request: PromptRequest,
    ) {
        check(ref, binding)
        if (binding != null) admissions.checkPrompt(binding, ref, target, request)
    }

    /** Current immutable metadata and host authority, checked again before late-open publication. */
    suspend fun check(ref: SessionRef, binding: SessionExecutionBinding?) {
        metadata.withLock {
            check(bindings.find(ref) == binding) { "Handle authority does not match permanent metadata" }
            if (binding == null) requireUnbound(ref.engine)
        }
        if (binding != null) admissions.check(binding)
    }

    override suspend fun fence(binding: SessionExecutionBinding, persist: suspend () -> Unit) =
        admissions.fence(binding, persist)

    override suspend fun awaitAdmissions(binding: SessionExecutionBinding) {
        admissions.awaitAdmissions(binding)
        requirePublished(binding)
    }

    override suspend fun <T> suspended(binding: SessionExecutionBinding, block: suspend () -> T): T =
        admissions.suspended(binding) {
            requirePublished(binding)
            block()
        }

    private suspend fun requirePublished(binding: SessionExecutionBinding) {
        retryBindings()
        metadata.withLock {
            check(bindings.pending().none { it.binding.instance == binding.instance }) {
                "Native creation remains unconfirmed"
            }
        }
    }

    /** Runtime refs remain inspectable even when their first durable binding write failed. */
    override suspend fun references(binding: SessionExecutionBinding): Set<SessionRef> = metadata.withLock {
        bindings.references(binding.instance) + quarantine.filterKeys { it.binding.instance == binding.instance }.values
    }

    private suspend fun <T> unbound(engine: EngineId, block: suspend () -> T): T {
        val ticket = metadata.withLock {
            requireUnbound(engine)
            CompletableDeferred<Unit>().also { unbound.getOrPut(engine) { mutableSetOf() }.add(it) }
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
                metadata.withLock {
                    if (isReturned) {
                        unbound[engine]?.remove(ticket)
                        if (unbound[engine]?.isEmpty() == true) unbound.remove(engine)
                        ticket.complete(Unit)
                    }
                }
            }
        }
    }

    private suspend fun requireUnbound(engine: EngineId) {
        check(bindings.pending().none { it.engine == engine }) { "Unbound native creation is quarantined" }
    }
}
