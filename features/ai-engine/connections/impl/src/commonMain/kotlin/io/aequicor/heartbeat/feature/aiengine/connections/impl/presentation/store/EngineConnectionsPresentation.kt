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
    /** Management of the selected engine; null while engine management is off. */
    val panel: EnginePanelUi? = null,
    /** Launch settings being edited for the selected engine; null shows the saved ones. */
    val launchDraft: LaunchDraftUi? = null,
    /** A code pasted from a sign-in page, kept until sent or its sign-in prompt ends. Never logged. */
    val loginCode: String = "",
    /** An action waiting for confirmation (uninstall, an unverified update, sign-out). */
    val confirmAction: EngineActionUi? = null,
) : MVIState {
    override fun toString(): String = "EngineConnectionsScreenState(***)"
}

/** Rebuilds the three levels, keeping the selection while it still exists and otherwise picking the first entry. */
internal fun EngineConnectionsScreenState.reflect(state: EngineConnectionsState): EngineConnectionsScreenState =
    when (state) {
        EngineConnectionsState.Idle -> copy(isLoading = true, loadFailure = null, loginCode = "")

        is EngineConnectionsState.Active -> {
            val base = copy(
                isLoading = state.snapshot == null && state.loadFailure == null,
                isSaving = state.pending != null,
                loadFailure = state.loadFailure?.toUi(),
                failure = state.failed?.failure?.toUi(),
                loginCode = loginCode.takeIf { state.snapshot != null }.orEmpty(),
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
                    panel = null,
                )

                else -> base
            }
        }
    }

/**
 * With engine management on, every registered engine is listed (switched-off ones too) and the selected one gets
 * its panel; connections and models exist only for engines that are on. Off, the space is exactly as before.
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
    val panel = management?.engines?.firstOrNull { it.descriptor.id.value == engineId }
        ?.toPanel(launchDraft.takeIf { engineId == selectedEngine }, management.platform)
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
    val isSameEngine = engineId == selectedEngine
    return copy(
        engines = engineRows.toImmutableList(),
        selectedEngine = engineId,
        connections = connectionRows.toImmutableList(),
        selectedConnection = connectionId,
        models = models,
        confirmDisconnect = confirmDisconnect?.takeIf { id -> connectionRows.any { it.id == id } },
        panel = panel,
        launchDraft = launchDraft.takeIf { isSameEngine && panel != null },
        loginCode = loginCode.takeIf { isSameEngine && panel?.job?.phase is JobPhaseUi.AwaitingCode }.orEmpty(),
        confirmAction = confirmAction.takeIf { isSameEngine && panel?.job?.isRunning != true },
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
