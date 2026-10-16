package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelModel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.fileOf
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.filePage
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_unsaved
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_dismiss
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_loading
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_configuration_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_files
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_files_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_missing
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_no_settings
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_settings
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_settings_description
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun EngineControlPanelScreen(
    model: EngineControlPanelModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by produceState(EngineControlPanelState(""), model) {
        model.store.collect { states.collect { value = it } }
    }
    EngineControlPanelContent(state, { model.store.intent(it) }, onBack, modifier)
}

/**
 * One engine: its management block, then the pages of its configuration. An open page replaces the overview and
 * its back action returns to it.
 */
@Composable
internal fun EngineControlPanelContent(
    state: EngineControlPanelState,
    onIntent: (EngineControlPanelIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.config
    val open = state.openPage
    if (form != null && open != null) {
        ConfigScreen(state, form, open, onIntent, modifier)
        return
    }
    ControlColumn(state.title.ifEmpty { state.engine }, onBack, modifier.testTag("engine-control-panel")) {
        when {
            state.hasLoadFailure -> LoadFailure({ onIntent(EngineControlPanelIntent.RetryLoad) })

            state.isLoading -> HbLoadingState(stringResource(Res.string.control_loading))

            state.isMissing -> HbEmptyState(stringResource(Res.string.control_panel_missing))

            else -> {
                state.failure?.let {
                    OperationFailure(
                        it,
                        onDismiss = { onIntent(EngineControlPanelIntent.DismissError) },
                    )
                }
                state.panel?.let { EngineManagementSection(it, state.loginCode, state.isSaving, onIntent) }
                SettingsPages(state, onIntent)
                state.config?.takeIf { it.files.isNotEmpty() }?.let { ConfigFiles(it, onIntent) }
            }
        }
    }
    val panel = state.panel
    val action = state.confirmAction
    if (panel != null && action != null) EngineActionDialog(action, panel, isEnabled = !state.isSaving, onIntent)
}

/** An open configuration page or file editor in place of the overview; back returns to the overview. */
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
    ControlColumn(title, { onIntent(ConfigIntent.ClosePage) }, modifier.testTag("engine-control-panel")) {
        state.failure?.let { OperationFailure(it, onDismiss = { onIntent(EngineControlPanelIntent.DismissError) }) }
        when {
            file != null -> ConfigFileContent(file, form, state.isSaving, onIntent)
            page != null -> ConfigPageContent(page, form, state.isSaving, onIntent)
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

@Composable
private fun OperationFailure(failure: FailureUi, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
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
