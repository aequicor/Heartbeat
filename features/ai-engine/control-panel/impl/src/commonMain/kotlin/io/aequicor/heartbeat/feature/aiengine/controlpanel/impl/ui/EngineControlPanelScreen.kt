package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.components.rememberHbBusyReveal
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelAction
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelModel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EnginePanelTabUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.fileOf
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.filePage
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_unsaved
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_dismiss
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_leave_body
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_leave_discard
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_leave_stay
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_leave_title
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_loading
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_configuration_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_connections_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_files
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_files_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_missing
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_no_settings
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_overview
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_resources_unsupported
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_settings
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_settings_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_tab_connections
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_tab_resources
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_tab_settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun EngineControlPanelScreen(
    model: EngineControlPanelModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isEmbedded: Boolean = false,
    connections: (@Composable () -> Unit)? = null,
    onShowConnections: () -> Unit = {},
) {
    val leave by rememberUpdatedState(onBack)
    val showConnections by rememberUpdatedState(onShowConnections)
    val state by produceState(EngineControlPanelState(""), model) {
        model.store.collect {
            launch {
                actions.collect {
                    when (it) {
                        EngineControlPanelAction.Leave -> leave()
                        EngineControlPanelAction.ShowConnections -> showConnections()
                    }
                }
            }
            states.collect { value = it }
        }
    }
    EngineControlPanelContent(state, { model.store.intent(it) }, modifier, isEmbedded, connections)
}

/**
 * One engine: management, retained connections, profile resources and declared settings in four tabs.
 * The connections child owns a bounded scroll area; other tabs share the panel's scrolling column.
 * An open page, file or saved project replaces the tabs; back respects unsaved configuration guards.
 */
@Composable
internal fun EngineControlPanelContent(
    state: EngineControlPanelState,
    onIntent: (EngineControlPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
    isEmbedded: Boolean = false,
    connections: (@Composable () -> Unit)? = null,
) {
    // The tab column keeps its place while a page, a file or a saved project is open; drafts stay in the store.
    val overview = rememberScrollState()
    val isSavingVisible = rememberHbBusyReveal(state.isBusy)
    if (state.isLeaving) LeaveDialog(onIntent)
    if (state.openProject != null) {
        ProjectScreen(state, onIntent, modifier)
        return
    }
    val form = state.config
    val open = state.openPage
    if (form != null && open != null) {
        ConfigScreen(state, form, open, onIntent, modifier)
        return
    }
    val back = { onIntent(EngineControlPanelIntent.Back) }
    val engineTitle = state.title.ifEmpty { state.engine }
    ControlColumn(
        engineTitle,
        back.takeUnless { isEmbedded },
        modifier.testTag("engine-control-panel"),
        overview,
        isContentScrollable = state.openTab != EnginePanelTabUi.Connections,
        headerActions = state.panel?.let { panel ->
            {
                EngineSwitchHeader(
                    panel,
                    isIdle = !state.isBusy && panel.job?.isRunning != true,
                    onIntent = { onIntent(it) },
                    isVisuallyIdle = !isSavingVisible && panel.job?.isRunning != true,
                )
            }
        },
    ) {
        when {
            state.hasLoadFailure -> LoadFailure({ onIntent(EngineControlPanelIntent.RetryLoad) })

            state.isLoading -> HbLoadingState(stringResource(Res.string.control_loading))

            state.isMissing -> HbEmptyState(stringResource(Res.string.control_panel_missing))

            else -> {
                PanelTabs(state.openTab, onIntent)
                PanelTabContent(state, onIntent, isSavingVisible, connections)
            }
        }
    }
    OverviewDialogs(state, onIntent)
}

@Composable
private fun ColumnScope.PanelTabContent(
    state: EngineControlPanelState,
    onIntent: (EngineControlPanelIntent) -> Unit,
    isSavingVisible: Boolean,
    connections: (@Composable () -> Unit)?,
) {
    when (state.openTab) {
        EnginePanelTabUi.Overview -> Overview(state, onIntent, isSavingVisible)

        EnginePanelTabUi.Connections -> Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.isConnectionsEnabled) {
                connections?.invoke()
            } else {
                HbEmptyState(stringResource(Res.string.control_panel_connections_off))
            }
        }

        EnginePanelTabUi.Resources -> ResourcesContent(state, onIntent)

        EnginePanelTabUi.Settings -> SettingsContent(state, onIntent)
    }
}

@Composable
private fun PanelTabs(selected: EnginePanelTabUi, onIntent: (EngineControlPanelIntent) -> Unit) {
    HbRow(Modifier.fillMaxWidth().testTag("engine-panel-tabs"), gap = HbTheme.spacing.xs) {
        EnginePanelTabUi.entries.forEach { tab ->
            val label = stringResource(
                when (tab) {
                    EnginePanelTabUi.Overview -> Res.string.control_panel_overview
                    EnginePanelTabUi.Connections -> Res.string.control_panel_tab_connections
                    EnginePanelTabUi.Resources -> Res.string.control_panel_tab_resources
                    EnginePanelTabUi.Settings -> Res.string.control_panel_tab_settings
                },
            )
            HbNavigationItem(
                label,
                { onIntent(EngineControlPanelIntent.SelectTab(tab)) },
                Modifier.weight(1f).testTag(
                    when (tab) {
                        EnginePanelTabUi.Overview -> "engine-tab-overview"
                        EnginePanelTabUi.Connections -> "engine-tab-connections"
                        EnginePanelTabUi.Resources -> "engine-tab-resources"
                        EnginePanelTabUi.Settings -> "engine-tab-settings"
                    },
                ),
                icon = HbIcons.ChevronRight,
                isSelected = tab == selected,
                role = Role.Tab,
                minHeight = HbTheme.dimensions.navigationRowHeight,
                selectedBackground = HbTheme.surfaces.selected,
                selectedForeground = HbTheme.surfaces.onSelected,
                contentColor = HbTheme.colors.textPrimary,
            )
        }
    }
}

/** The management block and its honest runtime state. */
@Composable
private fun Overview(
    state: EngineControlPanelState,
    onIntent: (EngineControlPanelIntent) -> Unit,
    isSavingVisible: Boolean,
) {
    state.failure?.let { ControlFailureBanner(it, onDismiss = { onIntent(EngineControlPanelIntent.DismissError) }) }
    state.panel?.let {
        EngineManagementSection(it, state.loginCode, state.isBusy, onIntent, isSavingVisible = isSavingVisible)
    }
}

/** Dynamic ConfigPage widgets, whole files and import declared by this engine's capabilities. */
@Composable
private fun SettingsContent(state: EngineControlPanelState, onIntent: (EngineControlPanelIntent) -> Unit) {
    state.failure?.let { ControlFailureBanner(it, onDismiss = { onIntent(EngineControlPanelIntent.DismissError) }) }
    SettingsPages(state, onIntent)
    state.config?.takeIf { it.files.isNotEmpty() }?.let { ConfigFiles(it, onIntent) }
    if (state.isImportable) ImportSection(isEnabled = !state.isBusy, onIntent)
}

/** Aggregated profile resources are read only through the control machine's ListResources operation. */
@Composable
private fun ResourcesContent(state: EngineControlPanelState, onIntent: (EngineControlPanelIntent) -> Unit) {
    state.failure?.let { ControlFailureBanner(it, onDismiss = { onIntent(EngineControlPanelIntent.DismissError) }) }
    if (state.hasResourceInventory) {
        ResourceContent(
            state.resources,
            state.isReadingResources,
            !state.isBusy,
            { onIntent(EngineControlPanelIntent.RefreshResources) },
        )
    } else {
        HbEmptyState(stringResource(Res.string.control_panel_resources_unsupported))
    }
}

/** A confirmation of an engine action, an import being chosen and the report of a finished one. */
@Composable
private fun OverviewDialogs(state: EngineControlPanelState, onIntent: (EngineControlPanelIntent) -> Unit) {
    val panel = state.panel
    val action = state.confirmAction
    if (panel != null && action != null) EngineActionDialog(action, panel, isEnabled = !state.isBusy, onIntent)
    state.importing?.let { ImportDialog(it, isBlocked = state.config?.isDirty == true, state.isBusy, onIntent) }
    state.importReport?.let { ImportReportDialog(it, onIntent) }
}

/** An open configuration page or file editor in place of the settings tab; back returns to settings. */
@Composable
private fun ConfigScreen(
    state: EngineControlPanelState,
    form: ConfigFormUi,
    open: String,
    onIntent: (EngineControlPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = fileOf(open)?.let { id -> form.files.firstOrNull { it.id == id } }
    val page = form.pages.firstOrNull { it.id == open }
    val title = file?.fileName ?: page?.title?.text() ?: state.title
    ControlColumn(
        title,
        { onIntent(EngineControlPanelIntent.Back) },
        modifier.testTag("engine-control-panel"),
        pinned = if (form.isDirty || form.hasConflict) {
            { ConfigSaveBar(form, state.isSaving, onIntent, isBusy = state.isBusy) }
        } else {
            null
        },
    ) {
        state.failure?.let { ControlFailureBanner(it, onDismiss = { onIntent(EngineControlPanelIntent.DismissError) }) }
        when {
            file != null -> ConfigFileContent(file, form, state.isSaving, onIntent)

            page != null -> {
                ConfigPageContent(page, form, state.isSaving, onIntent)
                if (page.hasResources) {
                    ResourceContent(
                        state.resources,
                        state.isReadingResources,
                        !state.isBusy,
                        { onIntent(EngineControlPanelIntent.RefreshResources) },
                    )
                }
            }
        }
    }
}

/** The profile's configuration files, each opening an editor of the whole file. */
@Composable
private fun ConfigFiles(
    form: ConfigFormUi,
    onIntent: (EngineControlPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.control_panel_files),
        modifier.testTag("engine-control-files"),
        description = stringResource(Res.string.control_panel_files_description),
    ) {
        form.files.forEachIndexed { index, file ->
            if (index > 0) HbDivider()
            HbSettingsRow(
                file.title.text(),
                Modifier.testTag("engine-control-file:${file.id}"),
                description = file.fileName,
                onClick = { onIntent(ConfigIntent.OpenPage(filePage(file.id))) },
            ) {
                HbIcon(HbIcons.ChevronRight, contentDescription = null)
            }
        }
    }
}

/** The failure of the last operation with its dismiss action. */
@Composable
internal fun ControlFailureBanner(failure: FailureUi, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    HbBanner(stringResource(failure.message()), modifier.testTag("engine-control-failure")) {
        HbButton(
            stringResource(Res.string.control_dismiss),
            onDismiss,
            style = HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun SettingsPages(
    state: EngineControlPanelState,
    onIntent: (EngineControlPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.control_panel_settings),
        modifier,
        description = stringResource(
            if (state.config?.isDirty == true) {
                Res.string.control_config_unsaved
            } else {
                Res.string.control_panel_settings_description
            },
        ),
    ) {
        when {
            state.isConfigurationOff -> HbBanner(
                stringResource(Res.string.control_panel_configuration_off),
                tone = HbTone.Neutral,
            )

            state.pages.isEmpty() -> HbEmptyState(stringResource(Res.string.control_panel_no_settings))

            else -> HbColumn(Modifier.fillMaxWidth().testTag("engine-control-pages"), gap = HbTheme.spacing.none) {
                state.pages.forEachIndexed { index, page ->
                    if (index > 0) HbDivider()
                    HbSettingsRow(
                        page.title.text(),
                        Modifier.testTag("engine-control-page:${page.id}"),
                        onClick = { onIntent(ConfigIntent.OpenPage(page.id)) },
                    ) {
                        HbIcon(HbIcons.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** Back was asked with unsaved changes: drop them and step back, or stay. */
@Composable
private fun LeaveDialog(onIntent: (EngineControlPanelIntent) -> Unit) {
    HbDialog(
        stringResource(Res.string.control_leave_title),
        onDismissRequest = { onIntent(EngineControlPanelIntent.KeepEditing) },
        actions = {
            HbButton(
                stringResource(Res.string.control_leave_stay),
                { onIntent(EngineControlPanelIntent.KeepEditing) },
                Modifier.testTag("leave-stay"),
                HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.control_leave_discard),
                { onIntent(EngineControlPanelIntent.DiscardAndLeave) },
                Modifier.testTag("leave-discard"),
                HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.control_leave_body), color = HbTheme.colors.textSecondary)
    }
}
