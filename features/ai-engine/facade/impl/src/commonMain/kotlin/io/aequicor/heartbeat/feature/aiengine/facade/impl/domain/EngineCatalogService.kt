package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [EngineCatalog] over the registry, toggles and saved bindings. Observation never probes: availability stays
 * Unknown until an explicit [refresh], except for statically unsupported platforms. Engine-wide capabilities
 * come from [engineFeatures] and are blocked while the engine is disabled.
 */
class EngineCatalogService(
    private val registry: EngineRegistry,
    private val toggles: EngineToggles,
    private val gate: EngineGate,
    private val bindings: StateFlow<List<EngineBinding>>,
    private val context: FacadeContext,
    private val engineFeatures: (EngineRegistration) -> EngineFeatures,
) : EngineCatalog {
    private val log = Log.tag("EngineCatalog")
    private val probes = MutableStateFlow(emptyMap<EngineId, Probe>())

    /** Engines enabled by toggles right now; synchronous capability resolution reads it. */
    val enabled: StateFlow<Set<EngineId>> = enabledEngines().stateIn(context.scope, SharingStarted.Eagerly, emptySet())

    override val state: StateFlow<List<EngineInfo>> = combine(enabled, probes, bindings) { on, probed, saved ->
        registry.all.filter { it.descriptor.id in on }.map { info(it, probed, saved) }
    }.stateIn(context.scope, SharingStarted.Eagerly, emptyList())

    override suspend fun refresh(engine: EngineId): EngineInfo {
        log.i { "refresh engine=${engine.value}" }
        val registration = gate.requireEnabled(engine)
        val availability = if (registry.supportsPlatform(registration)) {
            probe(registration)
        } else {
            EngineAvailability.UnsupportedPlatform
        }
        log.i { "availability engine=${engine.value} result=${availability.label()}" }
        probes.update { it + (engine to Probe(availability, Observation(context.clock.now(), isStale = false))) }
        return info(registration, probes.value, bindings.value)
    }

    override fun features(engine: EngineId): EngineFeatures {
        val registration = registry.find(engine) ?: return NoEngineFeatures
        return if (engine in enabled.value) engineFeatures(registration) else BlockedEngineFeatures(EngineUnavailable)
    }

    private suspend fun probe(registration: EngineRegistration): EngineAvailability = try {
        withContext(context.io) { registration.factory.value.checkRequirements() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e) { "requirements check failed engine=${registration.descriptor.id.value}" }
        EngineAvailability.Unavailable(e.failure)
    } catch (e: Exception) {
        log.e(e) { "requirements check crashed engine=${registration.descriptor.id.value}" }
        EngineAvailability.Unavailable(EngineFailure.Unknown())
    }

    private fun enabledEngines(): Flow<Set<EngineId>> {
        if (registry.all.isEmpty()) return flowOf(emptySet())
        val flags = registry.all.map { registration ->
            toggles.observe(registration.descriptor).map { on -> registration.descriptor.id.takeIf { on } }
        }
        return combine(flags) { ids -> ids.filterNotNull().toSet() }
    }

    private fun info(registration: EngineRegistration, probed: Map<EngineId, Probe>, saved: List<EngineBinding>) =
        registration.descriptor.id.let { id ->
            val probe = probed[id] ?: Probe(
                if (registry.supportsPlatform(registration)) {
                    EngineAvailability.Unknown
                } else {
                    EngineAvailability.UnsupportedPlatform
                },
                Observation(),
            )
            EngineInfo(registration.descriptor, probe.availability, saved.filter { it.engine == id }, probe.observation)
        }

    private data class Probe(val availability: EngineAvailability, val observation: Observation)
}

private fun EngineAvailability.label(): String = when (this) {
    EngineAvailability.Unknown -> "unknown"
    EngineAvailability.Available -> "available"
    EngineAvailability.UnsupportedPlatform -> "unsupported-platform"
    is EngineAvailability.Unavailable -> failure.code
}
