package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Models the user enabled through one connection. */
@Serializable
public data class BindingModels(val binding: EngineBindingId, val models: Set<ModelId>)

/**
 * Opaque per-selection generation for model checks. This is not an authentication revision or an access permit.
 * Malformed or ambiguous generation values are unknown without invalidating the selected models.
 */
@Serializable
public data class SelectedModelGeneration(val binding: EngineBindingId, val model: ModelId, val value: String) {
    override fun toString(): String = "SelectedModelGeneration"
}

/**
 * Profile choice of usable models: which discovered models each connection offers to model pickers, and the
 * default target. Discovery (ModelCatalog) says what a route can reach; this says what the user wants to see.
 * Invariant: the default target is always enabled.
 */
@Serializable
public data class ModelSelection(
    val bindings: List<BindingModels> = emptyList(),
    val defaultTarget: EngineTarget? = null,
    /** Storage-owned sidecar metadata. Transient keeps the model_selection JSON readable by older Heartbeat builds. */
    @Transient
    val generations: List<SelectedModelGeneration> = emptyList(),
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

    /** Current opaque generation, or null for a disabled model or missing/malformed/ambiguous metadata. */
    public fun checkGeneration(target: EngineTarget): String? = checkGeneration(target.binding, target.model)

    /** Binding/model lookup without inventing an engine identity; callers separately validate the full route. */
    public fun checkGeneration(binding: EngineBindingId, model: ModelId): String? {
        if (model !in enabled(binding)) return null
        return generations.singleOrNull {
            it.binding == binding && it.model == model
        }?.value?.takeIf { it.matches(SELECTION_GENERATION) }
    }

    /**
     * Replaces the models of [binding] without rebasing concurrent edits. A default that is no longer enabled is
     * cleared; a default of another binding is kept, and no replacement default is chosen implicitly.
     */
    public fun withEnabled(binding: EngineBindingId, models: Set<ModelId>): ModelSelection {
        val rest = bindings.filterNot { it.binding == binding }
        val updated = if (models.isEmpty()) rest else rest + BindingModels(binding, models)
        val default = defaultTarget?.takeUnless { it.binding == binding && it.model !in models }
        return copy(
            bindings = updated,
            defaultTarget = default,
            generations = generations.filter { it.binding != binding || it.model in models },
        )
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

    /**
     * Atomically applies [change] to stored choices. Storage preserves each unchanged enabled model's generation,
     * creates a fresh one for a new/re-enabled model, and removes disabled generations. Caller-supplied generations
     * are ignored. A default-only change never invalidates model checks. Reading old profiles does not migrate/write.
     * Generation metadata is separate from backward-compatible choices. Unconfirmed metadata remains unknown even
     * when the choice itself was saved; a metadata finalization failure never falsely reports a failed choice save.
     */
    public suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection
}

private val SELECTION_GENERATION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
