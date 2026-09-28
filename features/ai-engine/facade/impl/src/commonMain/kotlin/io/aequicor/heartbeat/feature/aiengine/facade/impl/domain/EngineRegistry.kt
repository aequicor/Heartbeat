package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.validateEngineRegistrations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock

/**
 * Validated registration set contributed by the application bundle. Construction performs no IO and never
 * touches a factory; [platform] is null on hosts without any engine support.
 */
class EngineRegistry(registrations: Collection<EngineRegistration>, val platform: EnginePlatform?) {
    init {
        validateEngineRegistrations(registrations)
    }

    /** Registrations in a stable order. */
    val all: List<EngineRegistration> = registrations.sortedBy { it.descriptor.id.value }

    private val byId = all.associateBy { it.descriptor.id }
    private val log = Log.tag("EngineRegistry")

    /** Registration of [engine], or null. */
    fun find(engine: EngineId): EngineRegistration? = byId[engine]

    /** Registration of [engine]; an unknown engine is reported as unavailable. */
    fun require(engine: EngineId): EngineRegistration = find(engine) ?: run {
        log.w { "unknown engine=${engine.value}" }
        fail(EngineUnavailable)
    }

    /** Whether [registration] declares the current host platform. */
    fun supportsPlatform(registration: EngineRegistration): Boolean =
        platform != null && platform in registration.descriptor.platforms

    /** Configured store that owns [ref], or null. */
    fun source(ref: SessionRef): EngineSessionSource? =
        find(ref.engine)?.sessionSources?.firstOrNull { it.source.id == ref.source }
}

/** Engines currently enabled by toggles; synchronous capability resolution and catalog filters read it. */
class EnabledEngines(val registry: EngineRegistry, toggles: EngineToggles, scope: CoroutineScope) {
    private val read: suspend () -> Set<EngineId> = {
        registry.all.filter { toggles.isEnabled(it.descriptor) }.mapTo(mutableSetOf()) { it.descriptor.id }
    }

    /** Enabled engine ids; empty until toggles are read. */
    val state: StateFlow<Set<EngineId>> = if (registry.all.isEmpty()) {
        MutableStateFlow(emptySet())
    } else {
        combine(
            registry.all.map { registration ->
                toggles.observe(registration.descriptor).map { on -> registration.descriptor.id.takeIf { on } }
            },
        ) { ids -> ids.filterNotNull().toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    }

    /** Enabled engine ids read from the toggles now; never the empty placeholder of an unread state. */
    suspend fun current(): Set<EngineId> = read()
}

/** Toggle gate of engines: the global AI-engine flag combined with the engine's own flag. */
interface EngineToggles {
    /** Current value and changes. */
    fun observe(descriptor: EngineDescriptor): Flow<Boolean>

    /** Current value. */
    suspend fun isEnabled(descriptor: EngineDescriptor): Boolean
}

/** Throws the domain failure [failure]. */
fun fail(failure: EngineFailure): Nothing = throw EngineException(failure)

/** Unknown, disabled or unsupported engine. */
val EngineUnavailable: EngineFailure = EngineFailure.Engine(EngineFailureReason.Unavailable)

/** Missing, foreign or disabled binding, or an operation forbidden by the current state. */
val OperationNotAllowed: EngineFailure = EngineFailure.Access(AccessFailureReason.OperationNotAllowed)

/** Malformed or inconsistent request. */
val InvalidRequest: EngineFailure = EngineFailure.Request(RequestFailureReason.Invalid)

/**
 * Profile environment shared by facade services: the profile coroutine [scope], wall [clock], the [io] dispatcher
 * for adapter calls that may block, and a source of random `[a-z0-9]` tokens for identifiers.
 */
class FacadeContext(
    val scope: CoroutineScope,
    val clock: Clock,
    val io: CoroutineDispatcher,
    private val newToken: () -> String,
) {
    /** A fresh identifier: [prefix] followed by a random token. */
    fun token(prefix: String): String = prefix + newToken()
}
