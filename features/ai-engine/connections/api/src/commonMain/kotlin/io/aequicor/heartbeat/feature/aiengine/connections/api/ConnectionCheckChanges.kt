package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId

/** Explicit network retry needs a selected, enabled exact route. Writes may preserve unavailable choices. */
internal fun ConnectionsSnapshot?.acceptsCheck(operation: ConnectionOperation): Boolean {
    if (operation !is ConnectionOperation.CheckModel) return true
    return this?.isModelCheckAvailable(operation.target) == true
}

/** Cached affordance for an explicit check; the machine and facade still revalidate on use. Never performs IO. */
public fun ConnectionsSnapshot.isModelCheckAvailable(target: EngineTarget): Boolean {
    val engine = engines.singleOrNull { it.descriptor.id == target.engine }
    val binding = engine?.bindings?.singleOrNull { it.id == target.binding && it.engine == target.engine }
    return selection.isEnabled(target) && engine?.availability == EngineAvailability.Available &&
        binding?.isEnabled == true && sources.count { it.info.id == binding.authSource } == 1
}

/** Snapshot-derived intent delta. Storage rebases it against the actual latest choices before writing. */
internal fun EngineConnectionsState.Active.execute(operation: ConnectionOperation): EngineConnectionsEffect.Execute {
    val snapshot = requireNotNull(snapshot)
    val targets = when (operation) {
        is ConnectionOperation.SetModelEnabled ->
            setOf(operation.target).filter { operation.isEnabled && !snapshot.selection.isEnabled(it) }.toSet()

        is ConnectionOperation.SetDefaultModel ->
            setOfNotNull(operation.target).filterNot { snapshot.selection.isEnabled(it) }.toSet()

        is ConnectionOperation.SetModelsEnabled -> snapshot.targets(
            operation.binding,
            (operation.models - (operation.previous ?: snapshot.selection.enabled(operation.binding))) -
                snapshot.selection.enabled(operation.binding),
        )

        is ConnectionOperation.CheckModel -> setOf(operation.target)

        is ConnectionOperation.ProbeEngine,
        is ConnectionOperation.SetConnectionEnabled,
        is ConnectionOperation.Disconnect,
        is ConnectionOperation.RefreshModels,
        -> emptySet()
    }
    val removed = when (operation) {
        is ConnectionOperation.SetModelEnabled -> if (operation.isEnabled) emptySet() else setOf(operation.target)

        is ConnectionOperation.SetModelsEnabled -> snapshot.targets(
            operation.binding,
            (operation.previous ?: snapshot.selection.enabled(operation.binding)) - operation.models,
        )

        is ConnectionOperation.ProbeEngine,
        is ConnectionOperation.SetConnectionEnabled,
        is ConnectionOperation.Disconnect,
        is ConnectionOperation.RefreshModels,
        is ConnectionOperation.SetDefaultModel,
        is ConnectionOperation.CheckModel,
        -> emptySet()
    }
    return EngineConnectionsEffect.Execute(operation, ConnectionOperationId(operationSequence + 1), targets, removed)
}

private fun ConnectionsSnapshot.targets(binding: EngineBindingId, models: Set<ModelId>): Set<EngineTarget> {
    val engine = engines.singleOrNull { info ->
        info.bindings.any { it.id == binding && it.engine == info.descriptor.id }
    }
    return models.mapNotNull { model -> engine?.descriptor?.id?.let { EngineTarget(it, binding, model) } }.toSet()
}
