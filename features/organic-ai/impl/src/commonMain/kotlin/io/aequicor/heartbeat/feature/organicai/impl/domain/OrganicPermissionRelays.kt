package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Live permission appearances of the existing cell observer; never persisted or restored as send authority. */
internal class OrganicPermissionRelays(
    private val isCurrent: (String, RequestId) -> Boolean = { _, _ ->
        false
    },
) {
    private val log = Log.tag("OrganicPermissionRelays")
    private val mutex = Mutex()
    private val appearances = mutableMapOf<String, List<Appearance>>()
    private var revision = 0L
    private val observers = mutableMapOf<String, Observer>()

    suspend fun begin(address: String, observer: String, request: RequestId): Boolean = mutex.withLock {
        if (!isCurrent(address, request)) return@withLock false
        observers[address] = Observer(observer, request)
        appearances.remove(address)
        true
    }

    suspend fun issue(
        claim: OrganicNativeClaim,
        ref: SessionRef,
        observer: String,
        pending: List<PermissionRequest>,
    ): List<PermissionRelay> = mutex.withLock {
        val lease = observers[claim.address] ?: return@withLock emptyList()
        if (lease.id != observer || !isCurrent(claim.address, lease.request)) return@withLock emptyList()
        val binding = claim.execution ?: return@withLock emptyList()
        val owner = binding.logicalSession?.owner ?: return@withLock emptyList()
        val old = appearances[claim.address].orEmpty()
        val current = pending.map { permission ->
            old.singleOrNull {
                it.observer == observer && it.relay.session == ref && it.relay.request == claim.request &&
                    it.relay.permission == permission
            } ?: Appearance(
                observer,
                PermissionRelay(
                    binding,
                    owner,
                    ref,
                    claim.target,
                    claim.request,
                    permission,
                    Uuid.random().toString(),
                    ++revision,
                ),
            )
        }
        if (current.isEmpty()) appearances.remove(claim.address) else appearances[claim.address] = current
        current.map { it.relay }
    }

    suspend fun forget(address: String, observer: String) = mutex.withLock {
        if (observers[address]?.id == observer) {
            observers.remove(address)
            appearances.remove(address)
        }
    }

    /** Canonical provider validates journal and machine first, then atomically consumes this exact appearance. */
    suspend fun consume(relay: PermissionRelay): Boolean = mutex.withLock {
        val appearance = appearances[relay.binding.instance.id]?.singleOrNull { it.relay == relay }
            ?: return@withLock false
        val observer = observers[relay.binding.instance.id] ?: return@withLock false
        if (observer.id != appearance.observer || !isCurrent(relay.binding.instance.id, observer.request)) {
            return@withLock false
        }
        if (appearance.isConsumed) return@withLock false
        appearances[relay.binding.instance.id] = appearances.getValue(relay.binding.instance.id).map {
            if (it === appearance) it.copy(isConsumed = true) else it
        }
        log.i { "Consume exact owner-mediated organic permission" }
        true
    }

    private data class Observer(val id: String, val request: RequestId)

    private data class Appearance(val observer: String, val relay: PermissionRelay, val isConsumed: Boolean = false)
}
