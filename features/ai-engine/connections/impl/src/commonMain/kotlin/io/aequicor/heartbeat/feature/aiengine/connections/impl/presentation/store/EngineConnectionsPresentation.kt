package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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

/** Three-level settings space derived from the machine snapshot and the local selection. */
@Immutable
data class EngineConnectionsScreenState(
    val engines: ImmutableList<EngineRowUi> = persistentListOf(),
    val selectedEngine: String? = null,
    val connections: ImmutableList<ConnectionRowUi> = persistentListOf(),
    val selectedConnection: String? = null,
    val models: ModelsPaneUi? = null,
    val modelQuery: String = "",
    val confirmDisconnect: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val loadFailure: FailureUi? = null,
    val failure: FailureUi? = null,
) : MVIState

/** Rebuilds the three levels, keeping the selection while it still exists and otherwise picking the first entry. */
internal fun EngineConnectionsScreenState.reflect(state: EngineConnectionsState): EngineConnectionsScreenState =
    when (state) {
        EngineConnectionsState.Idle -> copy(isLoading = true, loadFailure = null)

        is EngineConnectionsState.LoadError ->
            copy(isLoading = false, isSaving = false, loadFailure = state.failure.toUi())

        is EngineConnectionsState.Active -> {
            val base = copy(
                isLoading = state.snapshot == null,
                isSaving = state.pending != null,
                loadFailure = null,
                failure = state.failed?.failure?.toUi(),
            )
            state.snapshot?.let(base::withSnapshot) ?: base
        }
    }

private fun EngineConnectionsScreenState.withSnapshot(snapshot: ConnectionsSnapshot): EngineConnectionsScreenState {
    val engineRows = snapshot.engines.map { it.toRow() }
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
            origin = source?.scope?.origin?.value.orEmpty(),
            isEnabled = binding.isEnabled,
            enabledModels = snapshot.selection.enabled(binding.id).size,
        )
    }
    val connectionId = selectedConnection?.takeIf { id -> connectionRows.any { it.id == id } }
        ?: connectionRows.firstOrNull()?.id
    val binding = connectionId?.let(::EngineBindingId)
    val models = binding?.let { id ->
        val cached = snapshot.models[id]
        val enabled = snapshot.selection.enabled(id)
        ModelsPaneUi(
            models = cached?.models.orEmpty().map { model ->
                model.toRow(model.target.model in enabled, model.target == snapshot.selection.defaultTarget)
            }.toImmutableList(),
            isStale = cached?.observation?.isStale ?: true,
            isNeverSynced = cached?.observation?.checkedAt == null,
        )
    }
    return copy(
        engines = engineRows.toImmutableList(),
        selectedEngine = engineId,
        connections = connectionRows.toImmutableList(),
        selectedConnection = connectionId,
        models = models,
        confirmDisconnect = confirmDisconnect?.takeIf { id -> connectionRows.any { it.id == id } },
    )
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

/** Operation replacing the enabled models of the selected connection with all or none of its cached models. */
internal fun EngineConnectionsScreenState.allModels(isEnabled: Boolean): ConnectionOperation? {
    val binding = selectedConnection ?: return null
    val ids = if (isEnabled) models?.models.orEmpty().map { ModelId(it.id) }.toSet() else emptySet()
    return ConnectionOperation.SetModelsEnabled(EngineBindingId(binding), ids)
}
