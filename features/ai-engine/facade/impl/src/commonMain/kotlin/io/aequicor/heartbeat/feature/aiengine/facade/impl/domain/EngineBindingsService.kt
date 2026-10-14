package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.facade.api.BindingCheck
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Persistent bindings of one profile. Failures propagate. */
interface BindingStore {
    /** Current list and its changes. */
    fun observe(): Flow<List<EngineBinding>>

    /** Current list. */
    suspend fun load(): List<EngineBinding>

    /** Replaces the whole list atomically. */
    suspend fun save(bindings: List<EngineBinding>)
}

/** Forced runtime and handle cleanup after a binding was removed from persistent settings. */
fun interface BindingRelease {
    /** Stops execution through [binding], including accepted work without an open handle. Failures propagate. */
    suspend fun release(binding: EngineBinding)
}

/** Toggle and registration gate shared by every operation that may start engine work. */
class EngineGate(val registry: EngineRegistry, private val toggles: EngineToggles) {
    private val log = Log.tag("EngineGate")

    /** Registration of an engine enabled by toggles, rechecked at call time. */
    suspend fun requireEnabled(engine: EngineId): EngineRegistration {
        val registration = registry.require(engine)
        if (!toggles.isEnabled(registration.descriptor)) {
            log.w { "engine disabled by toggles engine=${engine.value}" }
            fail(EngineUnavailable)
        }
        return registration
    }
}

/**
 * [EngineBindings] over [BindingStore]. Mutations and [requireBinding] reads are serialized by one mutex, so a route
 * never observes a half-applied change. Ownership is checked through the registration (CLI logins stay with their
 * owner) and the adapter route is stored by [EngineFactory.bind] before a binding is saved; if that save fails for a
 * new binding, the route is compensated with [EngineFactory.unbind] and the save failure propagates.
 * Removal first saves the missing binding, then forcibly releases its runtime outside the settings mutex, so
 * shutdown callbacks can read routes. A failed release is remembered for a retry. [EngineFactory.unbind] follows
 * a successful release; a failing unbind is only logged because its stale adapter route is unreachable.
 * Concurrent session opening rechecks the removed route before returning a handle. Authentication itself is
 * checked only on request.
 */
class EngineBindingsService(
    private val gate: EngineGate,
    private val store: BindingStore,
    private val sources: AuthSources,
    private val checks: AuthChecks,
    private val release: BindingRelease,
    private val context: FacadeContext,
) : EngineBindings {
    private val log = Log.tag("EngineBindings")
    private val mutex = Mutex()
    private val disconnectMutex = Mutex()
    private val pendingRemovals = mutableMapOf<EngineBindingId, EngineBinding>()

    override val state: StateFlow<List<EngineBinding>> =
        store.observe().stateIn(context.scope, SharingStarted.Eagerly, emptyList())

    override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int): EngineBinding {
        log.i { "connect engine=${engine.value} source=${source.value} priority=$priority" }
        val registration = gate.requireEnabled(engine)
        var authSource = requireSource(source)
        // Cheap scope check first, so a rejected connect never persists a refreshed source revision.
        requireAccepted(registration, authSource, EngineContext(engine, PROVISIONAL_BINDING))
        val factory = registration.factory.value
        // The owner may be slow (a CLI probe); read it outside the lock so other binding edits are not blocked.
        val revision = adapterCall(log, "sourceRevision") { factory.sourceRevision(authSource) }
        if (revision != authSource.info.revision) {
            authSource = sources.updateRevision(source, revision)
            log.i { "source revision refreshed engine=${engine.value} source=${source.value}" }
        }
        return mutex.withLock {
            val saved = store.load()
            requireUniqueLogin(engine, authSource, saved)
            val existing = saved.firstOrNull { it.engine == engine && it.authSource == source }
            val id = existing?.id ?: EngineBindingId(context.token(BINDING_PREFIX))
            requireAccepted(registration, authSource, EngineContext(engine, id))
            factory.bind(id, authSource)
            val binding = existing?.copy(priority = priority) ?: EngineBinding(id, engine, source, priority = priority)
            saveBound(saved, binding, isNew = existing == null, factory)
            log.i { "binding saved binding=${id.value} new=${existing == null}" }
            binding
        }
    }

    private suspend fun requireUniqueLogin(engine: EngineId, source: AuthSource, saved: List<EngineBinding>) {
        if (source !is AuthSource.CliLogin) return
        val hasDuplicate = saved.filter { it.engine == engine && it.authSource != source.info.id }.any {
            val other = sources.get(it.authSource) as? AuthSource.CliLogin
            other?.owner == source.owner && other.location == source.location
        }
        if (hasDuplicate) {
            log.w { "connect rejected: login already bound engine=${engine.value} source=${source.info.id.value}" }
            fail(OperationNotAllowed)
        }
    }

    override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean): Unit = mutex.withLock {
        log.i { "set enabled binding=${binding.value} enabled=$enabled" }
        val saved = store.load()
        if (saved.none { it.id == binding }) fail(OperationNotAllowed)
        store.save(saved.map { if (it.id == binding) it.copy(isEnabled = enabled) else it })
    }

    override suspend fun disconnect(binding: EngineBindingId): Unit = disconnectMutex.withLock {
        // Once removal starts, caller cancellation must not leave its accepted work running without a route.
        withContext(NonCancellable) { removeAndRelease(binding) }
    }

    private suspend fun removeAndRelease(binding: EngineBindingId) {
        log.i { "disconnect binding=${binding.value}" }
        val removed = mutex.withLock {
            val saved = store.load()
            val existing = saved.firstOrNull { it.id == binding }
            if (existing != null) {
                store.save(saved.filterNot { it.id == binding })
                pendingRemovals[binding] = existing
            }
            pendingRemovals[binding]
        } ?: return
        release.release(removed)
        unbind(removed)
        mutex.withLock { pendingRemovals.remove(binding) }
    }

    private suspend fun unbind(removed: EngineBinding) {
        val binding = removed.id
        val factory = gate.registry.find(removed.engine)?.factory?.value
        if (factory == null) {
            log.w { "no registration to unbind engine=${removed.engine.value} binding=${binding.value}" }
            return
        }
        try {
            factory.unbind(binding)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "unbind failed after removal binding=${binding.value}" }
        }
    }

    /** Saves [binding] whose adapter route is already bound; a failed save of a new binding unbinds it again. */
    private suspend fun saveBound(
        saved: List<EngineBinding>,
        binding: EngineBinding,
        isNew: Boolean,
        factory: EngineFactory,
    ) {
        try {
            store.save(if (isNew) saved + binding else saved.map { if (it.id == binding.id) binding else it })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "binding save failed binding=${binding.id.value} new=$isNew" }
            if (isNew) compensate(factory, binding.id, e)
            throw e
        }
    }

    /** Removes the adapter route of a binding whose save failed; a secondary failure is attached to [cause]. */
    private suspend fun compensate(factory: EngineFactory, id: EngineBindingId, cause: Exception) {
        try {
            factory.unbind(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "compensating unbind failed binding=${id.value}" }
            cause.addSuppressed(e)
        }
    }

    override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?): BindingCheck {
        log.i { "check engine=${target.engine.value} binding=${target.binding.value}" }
        val registration = gate.requireEnabled(target.engine)
        val binding = requireBinding(target.binding, target.engine)
        val source = requireSource(binding.authSource)
        val context = EngineContext(target.engine, binding.id, workspace, target.model)
        requireAccepted(registration, source, context)
        val auth = checks.check(
            source.info.id,
            registration.factory.value.authContext(context),
            registration.authenticators,
        )
        return BindingCheck(binding.id, auth)
    }

    /** Saved binding [id] of [engine]; disabled bindings are returned too — callers decide. */
    suspend fun requireBinding(id: EngineBindingId, engine: EngineId): EngineBinding =
        saved().firstOrNull { it.id == id && it.engine == engine } ?: fail(OperationNotAllowed)

    /** Saved bindings, read consistently with in-flight mutations. */
    suspend fun saved(): List<EngineBinding> = mutex.withLock { store.load() }

    /** Source [id] of this profile, or an authentication failure when it was forgotten. */
    suspend fun requireSource(id: AuthSourceId): AuthSource =
        sources.get(id) ?: fail(authFailure(AuthFailureReason.SourceUnavailable, id))

    /** Fails unless [registration] may use [source] in [context]; ownership is checked first. */
    fun requireAccepted(registration: EngineRegistration, source: AuthSource, context: EngineContext) {
        if (!registration.accepts(source, context)) {
            log.w { "source rejected engine=${context.engine.value} source=${source.info.id.value}" }
            fail(authFailure(AuthFailureReason.AuthMismatch, source.info.id))
        }
    }

    private companion object {
        const val BINDING_PREFIX = "bnd_"

        /** Binding id of the pre-check, before the real id is known under the lock. */
        val PROVISIONAL_BINDING = EngineBindingId("bnd_provisional")
    }
}

/** Authentication failure for [source]. */
fun authFailure(reason: AuthFailureReason, source: AuthSourceId?): EngineFailure =
    EngineFailure.Authentication(AuthFailure(reason, source))
