package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.DescribedModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import pro.respawn.flowmvi.api.MVIState

/** One connection (binding) of the selected engine. [label] is user text and is never logged. */
@Immutable
data class ConnectionRowUi(
    val id: String,
    val label: String,
    val provider: String,
    val kind: MethodKindUi?,
    val origin: String,
    val isEnabled: Boolean,
    val enabledModels: Int,
)

/** Cached models of the selected connection; [isStale] and [isNeverSynced] explain an empty or old list. */
@Immutable
data class ModelsPaneUi(val models: ImmutableList<ModelRowUi>, val isStale: Boolean, val isNeverSynced: Boolean)

/** Three-level settings space derived from the machine snapshot and the local model draft. */
@Immutable
data class EngineConnectionsScreenState(
    val engines: ImmutableList<EngineRowUi> = persistentListOf(),
    val selectedEngine: String? = null,
    val connections: ImmutableList<ConnectionRowUi> = persistentListOf(),
    val selectedConnection: String? = null,
    val models: ModelsPaneUi? = null,
    val selectedModels: ImmutableSet<String> = persistentSetOf(),
    val modelDraft: ImmutableSet<String>? = null,
    /** Binding that owns [modelDraft]; a draft is never applied to another connection. */
    val modelDraftBinding: String? = null,
    /** Saved models from which the user's draft delta is computed and rebased on newer snapshots. */
    val modelDraftBaseline: ImmutableSet<String>? = null,
    val modelQuery: String = "",
    val confirmDisconnect: String? = null,
    /** Engine management is on: the selected engine is switched on and installed in its engine control panel. */
    val isManaged: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isModelSavePending: Boolean = false,
    val loadFailure: FailureUi? = null,
    val failure: FailureUi? = null,
    val checkDetails: ModelCheckDetailsUi? = null,
    /** Models described for adaptive subagents; null while that feature is off. */
    val descriptions: ImmutableSet<DescribedModel>? = null,
) : MVIState {
    override fun toString(): String = "EngineConnectionsScreenState(***)"
}

/** Rebuilds the three levels, keeping focus and an unsaved model draft while their connection remains selected. */
internal fun EngineConnectionsScreenState.reflect(state: EngineConnectionsState): EngineConnectionsScreenState =
    when (state) {
        EngineConnectionsState.Idle -> copy(
            isLoading = true,
            isModelSavePending = false,
            loadFailure = null,
            checkDetails = null,
        )

        is EngineConnectionsState.Active -> {
            val base = copy(
                isLoading = state.snapshot == null && state.loadFailure == null,
                isSaving = state.pending != null,
                isModelSavePending = state.pending is ConnectionOperation.SetModelsEnabled,
                loadFailure = state.loadFailure?.toUi(),
                failure = state.failed?.failure?.toUi(),
            )
            val snapshot = state.snapshot
            when {
                snapshot != null -> base.withSnapshot(snapshot)

                // Without an observed snapshot nothing can be changed, so stale rows are not offered.
                state.loadFailure != null -> base.copy(
                    engines = persistentListOf(),
                    connections = persistentListOf(),
                    models = null,
                    confirmDisconnect = null,
                    isManaged = false,
                    checkDetails = null,
                )

                else -> base
            }
        }
    }

/**
 * With engine management on, every registered engine is listed (switched-off ones too; the engine control panel
 * manages them); connections and models exist only for engines that are on. Off, the space is exactly as before.
 */
private fun EngineConnectionsScreenState.withSnapshot(snapshot: ConnectionsSnapshot): EngineConnectionsScreenState {
    val management = snapshot.management.takeIf { it.isEnabled }
    val engineRows = management?.engines?.map { managed ->
        val info = snapshot.engines.firstOrNull { it.descriptor.id == managed.descriptor.id }
        info?.toRow()?.copy(isEnabled = managed.enablement.isEnabled) ?: EngineRowUi(
            id = managed.descriptor.id.value,
            title = managed.descriptor.title,
            availability = managed.availability.toUi(),
            connections = managed.connections,
            isConnectable = false,
            isEnabled = false,
        )
    } ?: snapshot.engines.map { it.toRow() }
    val engineId = selectedEngine?.takeIf { id -> engineRows.any { it.id == id } } ?: engineRows.firstOrNull()?.id
    val engine = snapshot.engines.firstOrNull { it.descriptor.id.value == engineId }
    val providers = engine?.descriptor?.connectionMethods.orEmpty().associate { it.provider.id to it.provider.title }
    val connectionRows = engine?.bindings.orEmpty().map { binding ->
        val source = snapshot.sources.firstOrNull { it.info.id == binding.authSource }
        ConnectionRowUi(
            id = binding.id.value,
            label = source?.info?.label ?: binding.authSource.value,
            provider = source?.let { providers[it.scope.provider] ?: it.scope.provider.value }.orEmpty(),
            kind = source?.kind(),
            origin = source?.scope?.let { it.origin.value + it.basePath.orEmpty() }.orEmpty(),
            isEnabled = binding.isEnabled,
            enabledModels = snapshot.selection.enabled(binding.id).size,
        )
    }
    val connectionId = selectedConnection?.takeIf { id -> connectionRows.any { it.id == id } }
        ?: connectionRows.firstOrNull()?.id
    val binding = connectionId?.let(::EngineBindingId)
    val enabled = binding?.let { snapshot.selection.enabled(it) }.orEmpty().map { it.value }.toSet()
    val draft = rebaseModelDraft(connectionId, enabled)
    val models = binding?.let { modelsPane(snapshot, it, draft ?: enabled) }
    return copy(
        engines = engineRows.toImmutableList(),
        selectedEngine = engineId,
        connections = connectionRows.toImmutableList(),
        selectedConnection = connectionId,
        models = models,
        selectedModels = enabled.toImmutableSet(),
        modelDraft = draft,
        modelDraftBinding = connectionId.takeIf { draft != null },
        modelDraftBaseline = enabled.toImmutableSet().takeIf { draft != null },
        confirmDisconnect = confirmDisconnect?.takeIf { id -> connectionRows.any { it.id == id } },
        isManaged = management != null,
    ).refreshCheckDetails()
}

private fun EngineConnectionsScreenState.modelsPane(
    snapshot: ConnectionsSnapshot,
    binding: EngineBindingId,
    enabled: Set<String>,
): ModelsPaneUi {
    val cached = snapshot.models[binding]
    return ModelsPaneUi(
        models = cached?.models.orEmpty().map { model ->
            model.toRow(
                model.target.model.value in enabled,
                model.target == snapshot.selection.defaultTarget,
            ).copy(description = descriptionOf(model.target), check = snapshot.modelCheck(model.target))
        }.toImmutableList(),
        isStale = cached?.observation?.isStale ?: true,
        isNeverSynced = cached?.observation?.checkedAt == null,
    )
}

private fun EngineConnectionsScreenState.descriptionOf(target: EngineTarget): ModelDescriptionUi? =
    descriptions?.let { described ->
        if (DescribedModel(target.engine, target.model) in described) {
            ModelDescriptionUi.Described
        } else {
            ModelDescriptionUi.Missing
        }
    }

private fun AuthSource.kind(): MethodKindUi = when (this) {
    is AuthSource.ManagedKey, is AuthSource.ExternalKey, is AuthSource.CredentialHelper -> MethodKindUi.ApiKey
    is AuthSource.CliLogin -> MethodKindUi.CliLogin
    is AuthSource.NoAuth -> MethodKindUi.NoAuth
}

/** Target of [model] in the selected engine and connection, if both are selected. */
internal fun EngineConnectionsScreenState.target(model: String): EngineTarget? {
    val engine = selectedEngine ?: return null
    val binding = selectedConnection ?: return null
    return EngineTarget(EngineId(engine), EngineBindingId(binding), ModelId(model))
}

/** Edits one checkbox without writing; all other draft and saved selections stay intact. */
internal fun EngineConnectionsScreenState.editModel(model: String, isEnabled: Boolean): EngineConnectionsScreenState {
    if (selectedConnection == null || models?.models.orEmpty().none { it.id == model }) return this
    val current = modelDraft ?: selectedModels
    return withDraft(if (isEnabled) current + model else current - model)
}

/** Drafts all cached models, or none; a bulk edit likewise stays local until SaveModels. */
internal fun EngineConnectionsScreenState.editAllModels(isEnabled: Boolean): EngineConnectionsScreenState {
    if (selectedConnection == null) return this
    val cached = models?.models.orEmpty().map { it.id }
    val current = modelDraft ?: selectedModels
    return withDraft(if (isEnabled) current + cached else emptySet())
}

/** Drops local model intent and its save status when focus moves to another engine or connection. */
internal fun EngineConnectionsScreenState.clearModelDraft(): EngineConnectionsScreenState = copy(
    modelDraft = null,
    modelDraftBinding = null,
    modelDraftBaseline = null,
    isModelSavePending = false,
)

private fun EngineConnectionsScreenState.withDraft(models: Set<String>): EngineConnectionsScreenState {
    val binding = selectedConnection ?: return this
    val baseline = modelDraftBaseline.takeIf { modelDraft != null && modelDraftBinding == binding } ?: selectedModels
    val draft = models.toImmutableSet()
    return copy(
        modelDraft = draft,
        modelDraftBinding = binding,
        modelDraftBaseline = baseline,
        models = this.models?.let { pane ->
            pane.copy(models = pane.models.map { it.copy(isEnabled = it.id in draft) }.toImmutableList())
        },
    )
}

private fun EngineConnectionsScreenState.rebaseModelDraft(
    binding: String?,
    enabled: Set<String>,
): ImmutableSet<String>? {
    val previous = modelDraftBaseline ?: return null
    val draft = modelDraft?.takeIf { modelDraftBinding == binding } ?: return null
    return ((enabled - (previous - draft)) + (draft - previous)).takeIf { it != enabled }?.toImmutableSet()
}
