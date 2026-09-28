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
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistent bindings of one profile. Failures propagate. */
interface BindingStore {
    /** Current list and its changes. */
    fun observe(): Flow<List<EngineBinding>>

    /** Current list. */
    suspend fun load(): List<EngineBinding>

    /** Replaces the whole list atomically. */
    suspend fun save(bindings: List<EngineBinding>)
}

/** Whether active handles still execute through a binding; such bindings cannot be removed. */
fun interface BindingUsage {
    /** True while an open handle uses [binding]. */
    fun isInUse(binding: EngineBindingId): Boolean
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
 * [EngineBindings] over [BindingStore]. Mutations are serialized. Ownership is checked through the registration
 * (CLI logins stay with their owner) and the adapter route is stored by [EngineFactory.bind] before a binding is saved;
 * removal calls [EngineFactory.unbind] after the binding is gone. Authentication itself is checked only on request.
 */
class EngineBindingsService(
    private val gate: EngineGate,
    private val store: BindingStore,
    private val sources: AuthSources,
    private val checks: AuthChecks,
    private val usage: BindingUsage,
    private val context: FacadeContext,
) : EngineBindings {
    private val log = Log.tag("EngineBindings")
    private val mutex = Mutex()

    override val state: StateFlow<List<EngineBinding>> =
        store.observe().stateIn(context.scope, SharingStarted.Eagerly, emptyList())

    override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int): EngineBinding {
        log.i { "connect engine=${engine.value} source=${source.value} priority=$priority" }
        val registration = gate.requireEnabled(engine)
        val authSource = requireSource(source)
        return mutex.withLock {
            val saved = store.load()
            val existing = saved.firstOrNull { it.engine == engine && it.authSource == source }
            val id = existing?.id ?: EngineBindingId(context.token(BINDING_PREFIX))
            requireAccepted(registration, authSource, EngineContext(engine, id))
            registration.factory.value.bind(id, authSource)
            val binding = existing?.copy(priority = priority) ?: EngineBinding(id, engine, source, priority = priority)
            store.save(if (existing == null) saved + binding else saved.map { if (it.id == id) binding else it })
            log.i { "binding saved binding=${id.value} new=${existing == null}" }
            binding
        }
    }

    override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean): Unit = mutex.withLock {
        log.i { "set enabled binding=${binding.value} enabled=$enabled" }
        val saved = store.load()
        if (saved.none { it.id == binding }) fail(OperationNotAllowed)
        store.save(saved.map { if (it.id == binding) it.copy(isEnabled = enabled) else it })
    }

    override suspend fun disconnect(binding: EngineBindingId): Unit = mutex.withLock {
        log.i { "disconnect binding=${binding.value}" }
        if (usage.isInUse(binding)) fail(EngineFailure.Session(SessionFailureReason.Busy))
        val saved = store.load()
        val removed = saved.firstOrNull { it.id == binding } ?: return@withLock
        store.save(saved.filterNot { it.id == binding })
        gate.registry.find(removed.engine)?.factory?.value?.unbind(binding)
            ?: log.w { "no registration to unbind engine=${removed.engine.value} binding=${binding.value}" }
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
        store.load().firstOrNull { it.id == id && it.engine == engine } ?: fail(OperationNotAllowed)

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
    }
}

/** Authentication failure for [source]. */
fun authFailure(reason: AuthFailureReason, source: AuthSourceId?): EngineFailure =
    EngineFailure.Authentication(AuthFailure(reason, source))
