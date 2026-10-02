package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSearchField
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectionRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsModel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelsPaneUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.matches
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_cancel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_connections_count
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_loading
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_all
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_empty
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_none
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_search
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_search_clear
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_add_connection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_add_engine
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_connection_active
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_connection_off
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_connections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_default
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_description
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_disconnect
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_disconnect_confirm
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_disconnect_title
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_dismiss
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_enabled_models
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_engines
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_make_default
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_models
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_models_never
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_models_stale
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_no_connections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_probe
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_refresh_models
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_saving
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_select_connection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_title
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_engines_empty
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun EngineConnectionsScreen(
    model: EngineConnectionsModel,
    onAddConnection: (engine: String?) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by produceState(EngineConnectionsScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    EngineConnectionsContent(state, model.store::intent, onAddConnection, onBack, modifier)
}

/**
 * "Engine × connection × model" as one settings column: engines, then the connections of the selected engine,
 * then the models of the selected connection, each level as settings rows. Disconnecting asks in [HbDialog].
 * Inside the settings host [onBack] is null and only this content is drawn.
 */
@Composable
internal fun EngineConnectionsContent(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    onAddConnection: (engine: String?) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("engine-connections"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) StandaloneHeader(stringResource(Res.string.settings_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxWidth()
                    .hbVerticalScroll(rememberScrollState())
                    .padding(HbTheme.spacing.xl),
                gap = HbTheme.spacing.xxl,
            ) {
                ConnectionsToolbar(onAddEngine = { onAddConnection(null) })
                SettingsStatus(state, onIntent)
                EnginesSection(state, onIntent)
                state.panel?.let { EngineManagementSection(it, state.isSaving, onIntent) }
                ConnectionsSection(state, onIntent, onAddConnection)
                ModelsSection(state, onIntent)
            }
        }
    }
    val confirming = state.connections.firstOrNull { it.id == state.confirmDisconnect }
    if (confirming != null) DisconnectDialog(confirming.label, isEnabled = !state.isSaving, onIntent)
    val panel = state.panel
    val action = state.confirmAction
    if (panel != null && action != null) EngineActionDialog(action, panel, isEnabled = !state.isSaving, onIntent)
}

@Composable
private fun ConnectionsToolbar(onAddEngine: () -> Unit, modifier: Modifier = Modifier) {
    HbRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
        HbText(
            stringResource(Res.string.settings_description),
            Modifier.weight(1f),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbButton(stringResource(Res.string.settings_add_engine), onAddEngine, Modifier.testTag("settings-add-engine"))
    }
}

@Composable
private fun SettingsStatus(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loadFailure != null ->
            FailurePanel(state.loadFailure, modifier, onRetry = { onIntent(EngineConnectionsScreenIntent.RetryLoad) })

        state.failure != null -> FailurePanel(
            state.failure,
            modifier,
            onRetry = { onIntent(EngineConnectionsScreenIntent.RetryFailed) },
        ) {
            HbButton(
                stringResource(Res.string.settings_dismiss),
                { onIntent(EngineConnectionsScreenIntent.DismissError) },
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }

        state.isLoading -> HbLoadingState(stringResource(Res.string.conn_loading), modifier)

        state.isSaving -> HbLoadingState(stringResource(Res.string.settings_saving), modifier)
    }
}

@Composable
private fun EnginesSection(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.settings_engines),
        modifier,
        trailingContent = {
            if (state.selectedEngine != null) {
                HbButton(
                    stringResource(Res.string.settings_probe),
                    { onIntent(EngineConnectionsScreenIntent.ProbeEngine) },
                    Modifier.testTag("settings-probe"),
                    HbButtonStyle.Ghost,
                    enabled = !state.isSaving,
                    size = HbButtonSize.Small,
                )
            }
        },
    ) {
        if (!state.isLoading && state.engines.isEmpty()) {
            HbEmptyState(stringResource(Res.string.wizard_engines_empty))
        }
        HbColumn(Modifier.fillMaxWidth().selectableGroup().testTag("settings-engines"), gap = HbTheme.spacing.none) {
            state.engines.forEachIndexed { index, engine ->
                if (index > 0) HbDivider()
                EngineEntry(
                    engine,
                    isSelected = engine.id == state.selectedEngine,
                    onClick = { onIntent(EngineConnectionsScreenIntent.SelectEngine(engine.id)) },
                )
            }
        }
    }
}

@Composable
private fun EngineEntry(engine: EngineRowUi, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HbSettingsRow(
        engine.title,
        modifier.selectionSemantics(isSelected).testTag("settings-engine:${engine.id}"),
        description = stringResource(Res.string.conn_connections_count, engine.connections),
        onClick = onClick,
        isSelected = isSelected,
    ) {
        if (!engine.isEnabled) HbBadge(stringResource(Res.string.settings_connection_off))
        AvailabilityBadge(engine.availability)
    }
}

@Composable
private fun ConnectionsSection(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    onAddConnection: (engine: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A switched-off engine has no connections to manage; its panel explains why.
    val engine = state.engines.firstOrNull { it.id == state.selectedEngine }?.takeIf { it.isEnabled } ?: return
    HbSettingsSection(
        stringResource(Res.string.settings_connections),
        modifier,
        description = engine.title,
        trailingContent = {
            HbButton(
                stringResource(Res.string.settings_add_connection),
                { onAddConnection(engine.id) },
                Modifier.testTag("settings-add-connection"),
                HbButtonStyle.Secondary,
                enabled = engine.isConnectable,
                size = HbButtonSize.Small,
            )
        },
    ) {
        if (state.connections.isEmpty()) HbEmptyState(stringResource(Res.string.settings_no_connections))
        HbColumn(
            Modifier.fillMaxWidth().selectableGroup().testTag("settings-connections"),
            gap = HbTheme.spacing.none,
        ) {
            state.connections.forEachIndexed { index, connection ->
                if (index > 0) HbDivider()
                ConnectionEntry(
                    connection,
                    isSelected = connection.id == state.selectedConnection,
                    onClick = { onIntent(EngineConnectionsScreenIntent.SelectConnection(connection.id)) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionEntry(
    connection: ConnectionRowUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val details = listOfNotNull(
        connection.provider,
        connection.kind?.let { methodKindLabel(it) },
        stringResource(Res.string.settings_enabled_models, connection.enabledModels),
    ).joinToString(" · ")
    HbSettingsRow(
        connection.label,
        modifier.selectionSemantics(isSelected).testTag("settings-connection:${connection.id}"),
        description = details,
        onClick = onClick,
        isSelected = isSelected,
    ) {
        if (!connection.isEnabled) HbBadge(stringResource(Res.string.settings_connection_off))
    }
}

@Composable
private fun ModelsSection(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection = state.connections.firstOrNull { it.id == state.selectedConnection }
    val pane = state.models
    if (state.engines.none { it.id == state.selectedEngine && it.isEnabled }) return
    HbSettingsSection(stringResource(Res.string.settings_models), modifier, description = connection?.label) {
        if (connection == null || pane == null) {
            HbEmptyState(stringResource(Res.string.settings_select_connection))
            return@HbSettingsSection
        }
        ConnectionControls(connection, isEnabled = !state.isSaving, onIntent)
        HbDivider()
        ModelsList(pane, state.modelQuery, !state.isSaving, onIntent)
    }
}

@Composable
private fun ConnectionControls(
    connection: ConnectionRowUi,
    isEnabled: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = stringResource(Res.string.settings_connection_active)
    HbSettingsRow(active, modifier, description = connection.origin) {
        HbButton(
            stringResource(Res.string.settings_refresh_models),
            { onIntent(EngineConnectionsScreenIntent.RefreshModels) },
            Modifier.testTag("settings-refresh-models"),
            HbButtonStyle.Ghost,
            isEnabled,
            HbButtonSize.Small,
        )
        HbButton(
            stringResource(Res.string.settings_disconnect),
            { onIntent(EngineConnectionsScreenIntent.RequestDisconnect(connection.id)) },
            Modifier.testTag("settings-disconnect"),
            HbButtonStyle.Ghost,
            isEnabled,
            HbButtonSize.Small,
        )
        HbSwitch(
            connection.isEnabled,
            { onIntent(EngineConnectionsScreenIntent.SetConnectionEnabled(connection.id, it)) },
            active,
            Modifier.testTag("settings-connection-enabled"),
            isEnabled,
        )
    }
}

@Composable
private fun DisconnectDialog(label: String, isEnabled: Boolean, onIntent: (EngineConnectionsScreenIntent) -> Unit) {
    HbDialog(
        stringResource(Res.string.settings_disconnect_title),
        onDismissRequest = { onIntent(EngineConnectionsScreenIntent.DismissDisconnect) },
        actions = {
            HbButton(
                stringResource(Res.string.conn_cancel),
                { onIntent(EngineConnectionsScreenIntent.DismissDisconnect) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.settings_disconnect),
                { onIntent(EngineConnectionsScreenIntent.ConfirmDisconnect) },
                Modifier.testTag("settings-disconnect-confirm"),
                style = HbButtonStyle.Danger,
                enabled = isEnabled,
            )
        },
    ) {
        HbText(stringResource(Res.string.settings_disconnect_confirm, label), color = HbTheme.colors.textSecondary)
    }
}

@Composable
private fun ModelsList(
    pane: ModelsPaneUi,
    query: String,
    enabled: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(pane.models, query) { pane.models.filter { matches(query, it.title, it.id) } }
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbRow(Modifier.fillMaxWidth().padding(top = HbTheme.spacing.s), gap = HbTheme.spacing.s) {
            HbSearchField(
                query,
                { onIntent(EngineConnectionsScreenIntent.SearchModels(it)) },
                placeholder = stringResource(Res.string.conn_models_search),
                clearLabel = stringResource(Res.string.conn_models_search_clear),
                modifier = Modifier.weight(1f).testTag("settings-model-search"),
            )
            HbButton(
                stringResource(Res.string.conn_models_all),
                { onIntent(EngineConnectionsScreenIntent.SetAllModels(true)) },
                style = HbButtonStyle.Ghost,
                enabled = enabled && pane.models.isNotEmpty(),
                size = HbButtonSize.Small,
            )
            HbButton(
                stringResource(Res.string.conn_models_none),
                { onIntent(EngineConnectionsScreenIntent.SetAllModels(false)) },
                style = HbButtonStyle.Ghost,
                enabled = enabled && pane.models.any { it.isEnabled },
                size = HbButtonSize.Small,
            )
        }
        val note = when {
            pane.isNeverSynced -> Res.string.settings_models_never
            pane.models.isEmpty() -> Res.string.conn_models_empty
            pane.isStale -> Res.string.settings_models_stale
            else -> null
        }
        note?.let {
            HbText(stringResource(it), style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
        }
        HbColumn(Modifier.fillMaxWidth().testTag("settings-models"), gap = HbTheme.spacing.none) {
            visible.forEach { model ->
                ModelRow(
                    model,
                    enabled = enabled,
                    onEnabledChange = { onIntent(EngineConnectionsScreenIntent.SetModelEnabled(model.id, it)) },
                ) {
                    if (model.isDefault) {
                        HbBadge(stringResource(Res.string.settings_default), tone = HbTone.Brand)
                    } else {
                        HbButton(
                            stringResource(Res.string.settings_make_default),
                            { onIntent(EngineConnectionsScreenIntent.SetDefaultModel(model.id)) },
                            Modifier.testTag("settings-default:${model.id}"),
                            HbButtonStyle.Ghost,
                            enabled,
                            HbButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}

/** Exposes a row as one option of a single-choice list. */
private fun Modifier.selectionSemantics(isSelected: Boolean): Modifier = semantics {
    selected = isSelected
}
