package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectionRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsModel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelsPaneUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.matches
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_back
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_cancel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_connections_count
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_loading
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_all
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_empty
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_none
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_search
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_add_connection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_add_engine
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_connection_active
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_connection_off
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_connections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_default
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_description
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_disconnect
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_disconnect_confirm
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by produceState(EngineConnectionsScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    EngineConnectionsContent(state, model.store::intent, onAddConnection, onBack, modifier)
}

@Composable
internal fun EngineConnectionsContent(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    onAddConnection: (engine: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag("engine-connections").background(HbTheme.colors.background)) {
        HbColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(HbTheme.spacing.xl)) {
            SettingsHeader(onAddEngine = { onAddConnection(null) }, onBack = onBack)
            SettingsStatus(state, onIntent)
            HbBoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= HbTheme.dimensions.compactBreakpoint) {
                    HbRow(Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
                        EnginesPane(state, onIntent, isHorizontal = false, Modifier.weight(1f).fillMaxHeight())
                        ConnectionsPane(
                            state,
                            onIntent,
                            onAddConnection,
                            isHorizontal = false,
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                        ModelsPane(state, onIntent, Modifier.weight(2f).fillMaxHeight())
                    }
                } else {
                    HbColumn(Modifier.fillMaxSize()) {
                        EnginesPane(state, onIntent, isHorizontal = true)
                        ConnectionsPane(state, onIntent, onAddConnection, isHorizontal = true)
                        ModelsPane(state, onIntent, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(onAddEngine: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    HbColumn(modifier, gap = HbTheme.spacing.xs) {
        HbButton(stringResource(Res.string.conn_back), onBack, Modifier.testTag("settings-back"), HbButtonStyle.Quiet)
        HbText(stringResource(Res.string.settings_title), style = HbTheme.typography.display)
        HbText(stringResource(Res.string.settings_description), color = HbTheme.colors.textSecondary)
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
                style = HbButtonStyle.Quiet,
            )
        }

        state.isLoading -> HbText(
            stringResource(Res.string.conn_loading),
            modifier,
            color = HbTheme.colors.textSecondary,
        )

        state.isSaving -> HbText(
            stringResource(Res.string.settings_saving),
            modifier,
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun EnginesPane(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    isHorizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    Pane(stringResource(Res.string.settings_engines), modifier) {
        if (!state.isLoading && state.engines.isEmpty()) {
            HbText(stringResource(Res.string.wizard_engines_empty), color = HbTheme.colors.textSecondary)
        }
        val entryWidth = if (isHorizontal) HbTheme.dimensions.sidebarWidth else null
        SelectionList(isHorizontal, Modifier.weight(1f, fill = false).testTag("settings-engines")) {
            items(state.engines, key = { it.id }) { engine ->
                EngineEntry(
                    engine,
                    isSelected = engine.id == state.selectedEngine,
                    onClick = { onIntent(EngineConnectionsScreenIntent.SelectEngine(engine.id)) },
                    modifier = Modifier.entryWidth(entryWidth),
                )
            }
        }
        if (state.selectedEngine != null) {
            HbButton(
                stringResource(Res.string.settings_probe),
                { onIntent(EngineConnectionsScreenIntent.ProbeEngine) },
                Modifier.testTag("settings-probe"),
                HbButtonStyle.Secondary,
                enabled = !state.isSaving,
            )
        }
    }
}

@Composable
private fun EngineEntry(engine: EngineRowUi, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SelectableRow(
        isSelected = isSelected,
        onClick = onClick,
        modifier = modifier.testTag("settings-engine:${engine.id}"),
    ) {
        HbText(engine.title, style = HbTheme.typography.label)
        AvailabilityBadge(engine.availability)
        HbText(
            stringResource(Res.string.conn_connections_count, engine.connections),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ConnectionsPane(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    onAddConnection: (engine: String?) -> Unit,
    isHorizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val engine = state.engines.firstOrNull { it.id == state.selectedEngine }
    Pane(stringResource(Res.string.settings_connections), modifier) {
        if (engine != null && state.connections.isEmpty()) {
            HbText(stringResource(Res.string.settings_no_connections), color = HbTheme.colors.textSecondary)
        }
        val entryWidth = if (isHorizontal) HbTheme.dimensions.sidebarWidth else null
        SelectionList(isHorizontal, Modifier.weight(1f, fill = false).testTag("settings-connections")) {
            items(state.connections, key = { it.id }) { connection ->
                ConnectionEntry(
                    connection,
                    isSelected = connection.id == state.selectedConnection,
                    onClick = { onIntent(EngineConnectionsScreenIntent.SelectConnection(connection.id)) },
                    modifier = Modifier.entryWidth(entryWidth),
                )
            }
        }
        if (engine != null) {
            HbButton(
                stringResource(Res.string.settings_add_connection),
                { onAddConnection(engine.id) },
                Modifier.testTag("settings-add-connection"),
                HbButtonStyle.Secondary,
                enabled = engine.isConnectable,
            )
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
    SelectableRow(
        isSelected = isSelected,
        onClick = onClick,
        modifier = modifier.testTag("settings-connection:${connection.id}"),
    ) {
        HbText(connection.label, style = HbTheme.typography.label, maxLines = 1)
        HbText(
            listOfNotNull(connection.provider, connection.kind?.let { methodKindLabel(it) }).joinToString(" · "),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbFlowRow {
            if (!connection.isEnabled) HbBadge(stringResource(Res.string.settings_connection_off))
            HbText(
                stringResource(Res.string.settings_enabled_models, connection.enabledModels),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ModelsPane(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection = state.connections.firstOrNull { it.id == state.selectedConnection }
    val pane = state.models
    Pane(stringResource(Res.string.settings_models), modifier) {
        if (connection == null || pane == null) {
            HbText(stringResource(Res.string.settings_select_connection), color = HbTheme.colors.textSecondary)
            return@Pane
        }
        ConnectionHeader(connection, state, onIntent)
        ModelsList(pane, state.modelQuery, !state.isSaving, onIntent, Modifier.weight(1f))
    }
}

@Composable
private fun ConnectionHeader(
    connection: ConnectionRowUi,
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEnabled = !state.isSaving
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbText(connection.label, style = HbTheme.typography.title)
        HbText(connection.origin, style = HbTheme.typography.code, color = HbTheme.colors.textSecondary)
        HbFlowRow {
            HbSwitch(
                connection.isEnabled,
                { onIntent(EngineConnectionsScreenIntent.SetConnectionEnabled(connection.id, it)) },
                stringResource(Res.string.settings_connection_active),
                Modifier.testTag("settings-connection-enabled"),
                isEnabled,
            )
            HbButton(
                stringResource(Res.string.settings_refresh_models),
                { onIntent(EngineConnectionsScreenIntent.RefreshModels) },
                Modifier.testTag("settings-refresh-models"),
                HbButtonStyle.Secondary,
                isEnabled,
            )
            HbButton(
                stringResource(Res.string.settings_disconnect),
                { onIntent(EngineConnectionsScreenIntent.RequestDisconnect(connection.id)) },
                Modifier.testTag("settings-disconnect"),
                HbButtonStyle.Quiet,
                isEnabled,
            )
        }
        if (state.confirmDisconnect == connection.id) {
            DisconnectConfirmation(connection.label, isEnabled = !state.isSaving, onIntent)
        }
    }
}

@Composable
private fun DisconnectConfirmation(
    label: String,
    isEnabled: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbPanel(modifier.fillMaxWidth(), background = HbTheme.colors.warningContainer) {
        HbColumn(Modifier.padding(HbTheme.spacing.l), gap = HbTheme.spacing.s) {
            HbText(stringResource(Res.string.settings_disconnect_confirm, label))
            HbFlowRow {
                HbButton(
                    stringResource(Res.string.settings_disconnect),
                    { onIntent(EngineConnectionsScreenIntent.ConfirmDisconnect) },
                    Modifier.testTag("settings-disconnect-confirm"),
                    enabled = isEnabled,
                )
                HbButton(
                    stringResource(Res.string.conn_cancel),
                    { onIntent(EngineConnectionsScreenIntent.DismissDisconnect) },
                    style = HbButtonStyle.Quiet,
                )
            }
        }
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
        HbFlowRow(Modifier.fillMaxWidth()) {
            HbTextField(
                query,
                { onIntent(EngineConnectionsScreenIntent.SearchModels(it)) },
                Modifier.width(HbTheme.dimensions.composerMenuMinWidth).testTag("settings-model-search"),
                placeholder = stringResource(Res.string.conn_models_search),
            )
            HbButton(
                stringResource(Res.string.conn_models_all),
                { onIntent(EngineConnectionsScreenIntent.SetAllModels(true)) },
                style = HbButtonStyle.Secondary,
                enabled = enabled && pane.models.isNotEmpty(),
            )
            HbButton(
                stringResource(Res.string.conn_models_none),
                { onIntent(EngineConnectionsScreenIntent.SetAllModels(false)) },
                style = HbButtonStyle.Quiet,
                enabled = enabled && pane.models.any { it.isEnabled },
            )
        }
        val note = when {
            pane.isNeverSynced -> Res.string.settings_models_never
            pane.models.isEmpty() -> Res.string.conn_models_empty
            pane.isStale -> Res.string.settings_models_stale
            else -> null
        }
        note?.let {
            HbText(
                stringResource(it),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        HbLazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("settings-models"),
            gap = HbTheme.spacing.xs,
            contentPadding = PaddingValues(HbTheme.spacing.none),
        ) {
            items(visible, key = { it.id }) { model ->
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
                            HbButtonStyle.Quiet,
                            enabled,
                        )
                    }
                }
            }
        }
    }
}

/** Titled glass column of the settings space. */
@Composable
private fun Pane(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    HbPanel(modifier.fillMaxWidth()) {
        HbColumn(Modifier.padding(HbTheme.spacing.l), gap = HbTheme.spacing.m) {
            HbText(title, style = HbTheme.typography.title)
            content()
        }
    }
}

/** Lazy list that runs vertically in wide layouts and horizontally in compact ones. */
@Composable
private fun SelectionList(isHorizontal: Boolean, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val padding = PaddingValues(HbTheme.spacing.none)
    if (isHorizontal) {
        HbLazyRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.s, contentPadding = padding, content = content)
    } else {
        HbLazyColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs, contentPadding = padding, content = content)
    }
}

private fun Modifier.entryWidth(width: Dp?): Modifier = if (width != null) width(width) else this
