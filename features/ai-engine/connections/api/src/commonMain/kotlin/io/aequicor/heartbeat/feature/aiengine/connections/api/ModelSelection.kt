package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Models the user enabled through one connection. */
@Serializable
public data class BindingModels(val binding: EngineBindingId, val models: Set<ModelId>)

/**
 * Profile choice of usable models: which discovered models each connection offers to model pickers, and the
 * default target. Discovery (ModelCatalog) says what a route can reach; this says what the user wants to see.
 * Invariant: the default target is always enabled.
 */
@Serializable
public data class ModelSelection(
    val bindings: List<BindingModels> = emptyList(),
    val defaultTarget: EngineTarget? = null,
) {
    init {
        require(bindings.map { it.binding }.distinct().size == bindings.size) { "Duplicate binding" }
        require(defaultTarget == null || isEnabled(defaultTarget)) { "Default model must be enabled" }
    }

    /** Models enabled through [binding]. */
    public fun enabled(binding: EngineBindingId): Set<ModelId> =
        bindings.firstOrNull { it.binding == binding }?.models.orEmpty()

    /** Whether [target] is offered to model pickers. */
    public fun isEnabled(target: EngineTarget): Boolean = target.model in enabled(target.binding)

    /** Replaces the models of [binding]; a default that is no longer enabled is cleared. */
    public fun withEnabled(binding: EngineBindingId, models: Set<ModelId>): ModelSelection {
        val rest = bindings.filterNot { it.binding == binding }
        val updated = if (models.isEmpty()) rest else rest + BindingModels(binding, models)
        val default = defaultTarget?.takeUnless { it.binding == binding && it.model !in models }
        return ModelSelection(updated, default)
    }

    /** Enables or disables one model. */
    public fun withModel(target: EngineTarget, isEnabled: Boolean): ModelSelection {
        val models = enabled(target.binding)
        return withEnabled(target.binding, if (isEnabled) models + target.model else models - target.model)
    }

    /** Sets the default target, enabling it; null clears the default. */
    public fun withDefault(target: EngineTarget?): ModelSelection =
        if (target == null) copy(defaultTarget = null) else withModel(target, true).copy(defaultTarget = target)

    /** Drops everything chosen for a removed connection. */
    public fun without(binding: EngineBindingId): ModelSelection = withEnabled(binding, emptySet())
}

/**
 * Profile-persistent [ModelSelection]. Suspend operations are main-safe and propagate CancellationException.
 * Consumers observe it to build model pickers; ids alone never grant access — routes are checked on use.
 */
public interface ModelSelections {
    /** Current selection and its changes. */
    public fun observe(): Flow<ModelSelection>

    /** Atomically applies [change] to the stored selection and returns the result. */
    public suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection
}
