package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthException
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Observes the settings space and executes its changes through the facade, the source registry and selections. */
internal class EngineConnectionsEffects(
    private val services: EngineServices,
    private val selections: ModelSelections,
    private val checks: ModelChecks,
) : EffectHandler<EngineConnectionsEffect, EngineConnectionsIntent> {
    private val log = Log.tag("EngineConnectionsEffects")
    private val disconnectedSources = mutableMapOf<EngineBindingId, AuthSourceId>()

    override suspend fun handle(effect: EngineConnectionsEffect, machine: EffectScope<EngineConnectionsIntent>) {
        when (effect) {
            EngineConnectionsEffect.Observe -> snapshots().collect {
                machine.send(EngineConnectionsIntent.Internal.Snapshot(it))
            }

            is EngineConnectionsEffect.Execute -> execute(effect, machine)

            is EngineConnectionsEffect.Edit -> edit(effect, machine)
        }
    }

    private suspend fun edit(effect: EngineConnectionsEffect.Edit, machine: EffectScope<EngineConnectionsIntent>) {
        val sourceEdit = checks.beginSourceEdit(effect.request.expected.info.id)
        val related = services.facade.bindings.state.value
            .filter { it.authSource == effect.request.expected.info.id }
            .map { it.id }.toSet() + effect.relatedBindings
        try {
            related.forEach(checks::revoke)
        } catch (error: CancellationException) {
            checks.finishSourceEdit(sourceEdit)
            effect.request.key?.close()
            throw error
        } catch (error: Exception) {
            checks.finishSourceEdit(sourceEdit)
            effect.request.key?.close()
            throw error
        }
        val failure = MutableStateFlow<EngineFailure?>(null)
        val result = checks.saveAndCheck(
            effect.checkTargets,
            onSaved = { machine.send(EngineConnectionsIntent.Internal.Checking(effect.id)) },
            onFinished = {
                checks.finishSourceEdit(sourceEdit)
                effect.request.key?.close()
            },
            relatedBindings = related,
            relatedTargets = { selection -> relatedTargets(selection, effect.request.expected.info.id) },
            sourceEdit = sourceEdit,
        ) {
            try {
                val current = selections.observe().first()
                val isGenerationMissing = effect.checkTargets.any {
                    current.isEnabled(it) && current.checkGeneration(it) == null
                }
                val selection = if (isGenerationMissing) {
                    selections.update { it }
                } else {
                    current
                }
                editBinding(effect.request, failure)
                checks.finishSourceEdit(sourceEdit)
                // The source is already committed. Re-read choices atomically and initialize missing generations;
                // failure here must never turn the confirmed source write into a retryable edit failure.
                refreshSelection(selection)
            } finally {
                effect.request.key?.close()
            }
        }
        val completion = when (result) {
            is ModelCheckBatchResult.Completed -> EngineConnectionsIntent.Internal.Applied(effect.id)

            is ModelCheckBatchResult.TimedOut -> EngineConnectionsIntent.Internal.Applied(
                effect.id,
                isSavePending = result.saved == null,
            )

            is ModelCheckBatchResult.Failed -> if (result.saved == null) {
                EngineConnectionsIntent.Internal.ApplyFailed(failure.value ?: EngineFailure.Unknown(), effect.id)
            } else {
                EngineConnectionsIntent.Internal.Applied(effect.id)
            }
        }
        machine.send(completion)
    }

    private fun relatedTargets(selection: ModelSelection, source: AuthSourceId): Set<EngineTarget> =
        services.facade.bindings.state.value
            .filter { it.authSource == source && it.isEnabled }
            .flatMap { binding ->
                selection.enabled(binding.id).map { model -> EngineTarget(binding.engine, binding.id, model) }
            }.toSet()

    private suspend fun refreshSelection(previous: ModelSelection): ModelSelection = try {
        selections.update { it }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Could not refresh model choices after the connection was saved" }
        previous
    }

    private suspend fun editBinding(request: ConnectionEdit, failure: MutableStateFlow<EngineFailure?>) {
        try {
            services.facade.bindings.edit(request) {}
        } catch (error: AuthException) {
            failure.value = EngineFailure.Authentication(error.failure)
            throw error
        } catch (error: EngineException) {
            failure.value = error.failure
            throw error
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun snapshots(): Flow<ConnectionsSnapshot> {
        val facade = services.facade
        val snapshots = facade.engines.state.flatMapLatest { engines ->
            combine(
                cachedModels(engines),
                services.sources.state,
                selections.observe(),
                services.management.state,
            ) { models, sources, selection, management ->
                ConnectionsSnapshot(engines, sources, models, selection, management)
            }
        }
        return combine(snapshots, checks.observe()) { snapshot, history ->
            val context = ModelCheckContext(
                snapshot.selection,
                snapshot.engines,
                facade.bindings.state.value,
                snapshot.sources,
            )
            val matching = checks.current(history, context)
            val canonical = context.copy(engines = facade.engines.state.value, sources = services.sources.state.value)
            snapshot.copy(checks = checks.current(matching, canonical))
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

    private suspend fun execute(
        effect: EngineConnectionsEffect.Execute,
        machine: EffectScope<EngineConnectionsIntent>,
    ) {
        val completion = when (effect.operation) {
            is ConnectionOperation.SetModelEnabled,
            is ConnectionOperation.SetModelsEnabled,
            is ConnectionOperation.SetDefaultModel,
            is ConnectionOperation.CheckModel,
            -> saveModels(effect, machine)

            is ConnectionOperation.RefreshModels -> {
                executeControl(effect.operation)
                EngineConnectionsIntent.Internal.Applied(effect.id)
            }

            is ConnectionOperation.ProbeEngine,
            is ConnectionOperation.Edit,
            is ConnectionOperation.SetConnectionEnabled,
            is ConnectionOperation.Disconnect,
            -> {
                withContext(NonCancellable) { executeControl(effect.operation) }
                EngineConnectionsIntent.Internal.Applied(effect.id)
            }
        }
        machine.send(completion)
    }

    private suspend fun saveModels(
        effect: EngineConnectionsEffect.Execute,
        machine: EffectScope<EngineConnectionsIntent>,
    ): EngineConnectionsIntent.Internal {
        if (effect.removedTargets.isNotEmpty()) checks.revoke(effect.removedTargets)
        val result = checks.saveAndCheck(
            effect.checkTargets,
            onSaved = { machine.send(EngineConnectionsIntent.Internal.Checking(effect.id)) },
        ) { changeModels(effect.operation) }
        return when (result) {
            is ModelCheckBatchResult.Completed -> EngineConnectionsIntent.Internal.Applied(effect.id)

            is ModelCheckBatchResult.TimedOut -> EngineConnectionsIntent.Internal.Applied(
                effect.id,
                isSavePending = result.saved == null,
            )

            is ModelCheckBatchResult.Failed -> if (result.saved == null) {
                EngineConnectionsIntent.Internal.ApplyFailed(EngineFailure.Unknown(), effect.id)
            } else {
                EngineConnectionsIntent.Internal.Applied(effect.id)
            }
        }
    }

    private suspend fun executeControl(operation: ConnectionOperation) {
        val facade = services.facade
        when (operation) {
            is ConnectionOperation.ProbeEngine -> {
                log.i { "probe engine=${operation.engine.value}" }
                facade.engines.refresh(operation.engine)
            }

            is ConnectionOperation.SetConnectionEnabled -> {
                checks.revoke(operation.binding)
                log.i { "set connection enabled=${operation.isEnabled}" }
                facade.bindings.setEnabled(operation.binding, operation.isEnabled)
            }

            is ConnectionOperation.Disconnect -> disconnect(operation.binding)

            is ConnectionOperation.RefreshModels -> {
                log.i { "refresh models engine=${operation.engine.value}" }
                val snapshot = facade.models.refresh(operation.engine, operation.binding)
                log.d { "discovered models count=${snapshot.models.size}" }
            }

            is ConnectionOperation.SetModelEnabled,
            is ConnectionOperation.SetModelsEnabled,
            is ConnectionOperation.SetDefaultModel,
            is ConnectionOperation.CheckModel,
            is ConnectionOperation.Edit,
            -> error("Model operations require the bounded coordinator")
        }
    }

    private suspend fun changeModels(operation: ConnectionOperation): ModelSelection = when (operation) {
        is ConnectionOperation.SetModelEnabled -> {
            log.i { "set model enabled=${operation.isEnabled}" }
            selections.update { it.withModel(operation.target, operation.isEnabled) }
        }

        is ConnectionOperation.SetModelsEnabled -> {
            log.i { "set enabled models count=${operation.models.size}" }
            selections.update { selection ->
                val latest = selection.enabled(operation.binding)
                val models = operation.previous?.let { previous ->
                    (latest - (previous - operation.models)) + (operation.models - previous)
                } ?: operation.models
                selection.withEnabled(operation.binding, models)
            }
        }

        is ConnectionOperation.SetDefaultModel -> {
            log.i { "set default model present=${operation.target != null}" }
            selections.update { it.withDefault(operation.target) }
        }

        is ConnectionOperation.CheckModel -> selectionForCheck(operation.target)

        is ConnectionOperation.ProbeEngine,
        is ConnectionOperation.Edit,
        is ConnectionOperation.SetConnectionEnabled,
        is ConnectionOperation.Disconnect,
        is ConnectionOperation.RefreshModels,
        -> error("Control operations cannot mutate model choices")
    }

    /** Explicit retry may initialize missing diagnostic generations, but never changes choice/default values. */
    private suspend fun selectionForCheck(target: EngineTarget): ModelSelection {
        val current = selections.observe().first()
        return if (current.isEnabled(target) && current.checkGeneration(target) == null) {
            // Legacy profiles need a committed storage-owned UUID, not a nonce invented from a historical report.
            selections.update { it }
        } else {
            current
        }
    }

    /**
     * Removes the binding, then the source once no other binding uses it, then the model choice. The source of a
     * removed binding is remembered until it is forgotten, so a retry after a partial failure still removes it
     * and never strands a key. Changes run one at a time, so the map needs no synchronization.
     */
    private suspend fun disconnect(binding: EngineBindingId) {
        checks.revoke(binding)
        val bindings = services.facade.bindings
        bindings.state.value.firstOrNull { it.id == binding }?.authSource
            ?.let { disconnectedSources[binding] = it }
        // A removed binding can still have pending runtime cleanup after a failed disconnect.
        log.i { "disconnect binding" }
        bindings.disconnect(binding)
        val removed = disconnectedSources[binding]
        if (removed != null && bindings.state.value.none { it.id != binding && it.authSource == removed }) {
            log.i { "forget unused source" }
            services.sources.forget(removed)
        }
        disconnectedSources -= binding
        selections.update { it.without(binding) }
    }
}
