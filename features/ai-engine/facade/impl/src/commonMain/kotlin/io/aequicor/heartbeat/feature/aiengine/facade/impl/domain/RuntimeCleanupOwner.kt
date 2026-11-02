package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owned by one profile pool and retained by its awaited shutdown callback. A failed close never loses its
 * resource. Locks belong to individual object identities: equal credential identities or custom equals do not
 * coalesce different clients, and a stalled close does not prevent other sources from closing.
 */
internal class RuntimeCleanupOwner(private val context: FacadeContext) : RuntimeAdmissionCleanup {
    private val log = Log.tag("RuntimeCleanupOwner")
    private val owned = MutableStateFlow<List<Resource>>(emptyList())

    /** Unconfirmed closes, including attempts still in progress; this is not a count of live runtime sessions. */
    val pendingCount: Int get() = owned.value.size

    override suspend fun close(candidate: EngineRuntimeCandidate, expected: RuntimeIdentity) {
        track(candidate, expected, candidate::close).close()
    }

    override suspend fun close(runtime: EngineRuntime, expected: RuntimeIdentity) {
        track(runtime, expected, runtime::close).close()
    }

    /** Retries unconfirmed cleanup before another ordinary runtime of this engine/source is allowed to start. */
    suspend fun retryFor(engine: EngineId, source: AuthSourceId) {
        retry(owned.value.filter { it.identity.engine == engine && it.identity.source == source })
    }

    /** Called after acquisition/source locks have drained; every retained close is attempted independently. */
    suspend fun retryAll() {
        retry(owned.value)
    }

    private fun track(value: Any, identity: RuntimeIdentity, action: suspend () -> Unit): Resource {
        while (true) {
            val before = owned.value
            before.firstOrNull { it.value === value }?.let { return it }
            val resource = Resource(value, identity, action)
            if (owned.compareAndSet(before, before + resource)) {
                log.v { "Retained cleanup resource count=${before.size + 1}" }
                return resource
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun retry(resources: List<Resource>) {
        val failures = supervisorScope {
            val jobs = resources.map { resource -> async { resource.close() } }
            jobs.forEach { it.join() }
            jobs.mapNotNull { it.getCompletionExceptionOrNull() }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private inner class Resource(
        val value: Any,
        val identity: RuntimeIdentity,
        private val action: suspend () -> Unit,
    ) {
        private val mutex = Mutex()

        suspend fun close() {
            mutex.withLock {
                // A preceding initial close or retry may already have confirmed cleanup while we waited.
                if (owned.value.none { it === this@Resource }) return@withLock
                withContext(context.io) { action() }
                owned.update { entries -> entries.filterNot { it === this@Resource } }
                log.v { "Released confirmed cleanup resource" }
            }
        }
    }
}
