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
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelModel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_dismiss
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_loading
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_configuration_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_missing
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_no_settings
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_settings
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_panel_settings_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_version
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_version_unknown
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

/** One engine: its summary and the pages of its configuration. */
@Composable
internal fun EngineControlPanelContent(
    state: EngineControlPanelState,
    onIntent: (EngineControlPanelIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                Summary(state)
                SettingsPages(state)
            }
        }
    }
}

@Composable
private fun Summary(state: EngineControlPanelState, modifier: Modifier = Modifier) {
    HbSettingsRow(
        state.title,
        modifier.testTag("engine-control-summary"),
        description = state.version?.let { stringResource(Res.string.control_version, it) }
            ?: stringResource(Res.string.control_version_unknown),
    ) {
        StatusBadge(state.status)
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
private fun SettingsPages(state: EngineControlPanelState, modifier: Modifier = Modifier) {
    HbSettingsSection(
        stringResource(Res.string.control_panel_settings),
        modifier,
        description = stringResource(Res.string.control_panel_settings_description),
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
                    HbSettingsRow(page.title.text(), Modifier.testTag("engine-control-page:${page.id}"))
                }
            }
        }
    }
}
