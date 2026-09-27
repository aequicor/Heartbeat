package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Observes the settings space and executes its changes through the facade, the source registry and selections. */
class EngineConnectionsEffects(private val services: EngineServices, private val selections: ModelSelections) :
    EffectHandler<EngineConnectionsEffect, EngineConnectionsIntent> {
    private val log = Log.tag("EngineConnectionsEffects")

    override suspend fun handle(effect: EngineConnectionsEffect, machine: EffectScope<EngineConnectionsIntent>) {
        when (effect) {
            EngineConnectionsEffect.Observe -> snapshots().collect {
                machine.send(EngineConnectionsIntent.Internal.Snapshot(it))
            }

            is EngineConnectionsEffect.Execute -> {
                execute(effect.operation)
                machine.send(EngineConnectionsIntent.Internal.Applied)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun snapshots(): Flow<ConnectionsSnapshot> {
        val facade = services.facade
        return facade.engines.state.flatMapLatest { engines ->
            combine(cachedModels(engines), services.sources.state, selections.observe()) { models, sources, selection ->
                ConnectionsSnapshot(engines, sources, models, selection)
            }
        }
    }

    private fun cachedModels(engines: List<EngineInfo>): Flow<Map<EngineBindingId, ModelCatalogSnapshot>> {
        val flows = engines.flatMap { engine ->
            engine.bindings.map { binding ->
                services.facade.models.observe(engine.descriptor.id, binding.id).map { binding.id to it }
            }
        }
        return if (flows.isEmpty()) flowOf(emptyMap()) else combine(flows) { it.toMap() }
    }

    private suspend fun execute(operation: ConnectionOperation) {
        val facade = services.facade
        when (operation) {
            is ConnectionOperation.ProbeEngine -> {
                log.i { "probe engine=${operation.engine.value}" }
                facade.engines.refresh(operation.engine)
            }

            is ConnectionOperation.SetConnectionEnabled -> {
                log.i { "set connection enabled=${operation.isEnabled}" }
                facade.bindings.setEnabled(operation.binding, operation.isEnabled)
            }

            is ConnectionOperation.Disconnect -> disconnect(operation.binding)

            is ConnectionOperation.RefreshModels -> {
                log.i { "refresh models engine=${operation.engine.value}" }
                val snapshot = facade.models.refresh(operation.engine, operation.binding)
                log.d { "discovered models count=${snapshot.models.size}" }
            }

            is ConnectionOperation.SetModelEnabled -> {
                log.i { "set model enabled=${operation.isEnabled}" }
                selections.update { it.withModel(operation.target, operation.isEnabled) }
            }

            is ConnectionOperation.SetModelsEnabled -> {
                log.i { "set enabled models count=${operation.models.size}" }
                selections.update { it.withEnabled(operation.binding, operation.models) }
            }

            is ConnectionOperation.SetDefaultModel -> {
                log.i { "set default model present=${operation.target != null}" }
                selections.update { it.withDefault(operation.target) }
            }
        }
    }

    /** Removes the binding, its model choice and, once nothing else uses it, the source. */
    private suspend fun disconnect(binding: EngineBindingId) {
        val bindings = services.facade.bindings
        val source = bindings.state.value.firstOrNull { it.id == binding }?.authSource
        log.i { "disconnect binding" }
        bindings.disconnect(binding)
        selections.update { it.without(binding) }
        if (source != null && bindings.state.value.none { it.authSource == source }) {
            log.i { "forget unused source" }
            services.sources.forget(source)
        }
    }
}
