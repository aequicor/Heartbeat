package io.aequicor.heartbeat.feature.togglespanel.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbGlassScene
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.ToggleControlUi
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.ToggleRowUi
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelModel
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelScreenIntent
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelScreenState
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.Res
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_back
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_default
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_description
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_dismiss
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_empty
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_error
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_intro_description
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_loading
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_override
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_reset
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_reset_all
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_retry
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_saving
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_search
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_title
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_write_error
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun TogglesPanelScreen(model: TogglesPanelModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by produceState(TogglesPanelScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    TogglesPanelContent(state, model.store::intent, onBack, modifier)
}

@Composable
internal fun TogglesPanelContent(
    state: TogglesPanelScreenState,
    onIntent: (TogglesPanelScreenIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val introDescription = stringResource(Res.string.flags_intro_description)
    val groups = remember(state.rows, state.query, introDescription) {
        state.rows.filter { row -> matchesQuery(row, state.query.trim(), introDescription) }.groupBy { it.owner }
    }
    HbGlassScene(modifier.fillMaxSize().testTag("toggles-panel")) {
        HbLazyColumn(Modifier.fillMaxSize().safeDrawingPadding().testTag("flags-list")) {
            item(key = "header") { TogglesHeader(state, onIntent, onBack) }
            panelStatus(state, groups.isEmpty(), onIntent)
            groups.forEach { (owner, rows) ->
                item(key = "owner:$owner") { HbText(owner, style = HbTheme.typography.title) }
                items(rows, key = { it.key }) { row ->
                    ToggleRow(row, enabled = !state.isSaving && !state.hasLoadError, onApply = onIntent)
                }
            }
        }
    }
}

private fun matchesQuery(row: ToggleRowUi, query: String, introDescription: String): Boolean {
    val description = if (row.key == "welcome.cinematic_intro") introDescription else row.description
    return row.key.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)
}

@Composable
private fun TogglesHeader(
    state: TogglesPanelScreenState,
    onIntent: (TogglesPanelScreenIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier) {
        HbButton(stringResource(Res.string.flags_back), onBack, Modifier.testTag("flags-back"), HbButtonStyle.Quiet)
        HbText(stringResource(Res.string.flags_title), style = HbTheme.typography.display)
        HbText(stringResource(Res.string.flags_description), color = HbTheme.colors.textSecondary)
        HbTextField(
            state.query,
            { onIntent(TogglesPanelScreenIntent.Search(it)) },
            Modifier.fillMaxWidth().testTag("flags-search"),
            placeholder = stringResource(Res.string.flags_search),
        )
        HbButton(
            stringResource(Res.string.flags_reset_all),
            { onIntent(TogglesPanelScreenIntent.ResetAll) },
            Modifier.testTag("flags-reset-all"),
            HbButtonStyle.Secondary,
            enabled = !state.isLoading && !state.hasLoadError && !state.isSaving,
        )
    }
}

private fun LazyListScope.panelStatus(
    state: TogglesPanelScreenState,
    empty: Boolean,
    event: (TogglesPanelScreenIntent) -> Unit,
) {
    if (state.isLoading || state.isSaving) {
        item(key = "progress") {
            HbText(stringResource(if (state.isLoading) Res.string.flags_loading else Res.string.flags_saving))
        }
    }
    if (state.hasLoadError || state.hasWriteError) {
        item(key = "error") { PanelError(state.hasLoadError, state.isSaving, event) }
    }
    if (!state.isLoading && !state.hasLoadError && empty) {
        item(key = "empty") { HbText(stringResource(Res.string.flags_empty)) }
    }
}

@Composable
private fun PanelError(
    hasLoadError: Boolean,
    isSaving: Boolean,
    event: (TogglesPanelScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val retry = if (hasLoadError) TogglesPanelScreenIntent.RetryLoad else TogglesPanelScreenIntent.RetryWrite
    HbPanel(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        HbColumn(Modifier.padding(HbTheme.spacing.l)) {
            HbText(stringResource(if (hasLoadError) Res.string.flags_error else Res.string.flags_write_error))
            HbFlowRow {
                HbButton(
                    stringResource(Res.string.flags_retry),
                    { event(retry) },
                    Modifier.testTag("flags-retry"),
                    enabled = !isSaving,
                )
                if (!hasLoadError) {
                    HbButton(
                        stringResource(Res.string.flags_dismiss),
                        { event(TogglesPanelScreenIntent.DismissError) },
                        style = HbButtonStyle.Quiet,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    row: ToggleRowUi,
    enabled: Boolean,
    onApply: (TogglesPanelScreenIntent.Mutation) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbPanel(modifier.fillMaxWidth()) {
        HbColumn(Modifier.padding(HbTheme.spacing.l)) {
            HbText(row.key, style = HbTheme.typography.code)
            HbText(
                if (row.key == "welcome.cinematic_intro") {
                    stringResource(Res.string.flags_intro_description)
                } else {
                    row.description
                },
            )
            HbFlowRow {
                HbText(
                    stringResource(if (row.isOverridden) Res.string.flags_override else Res.string.flags_default),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
                HbButton(
                    stringResource(Res.string.flags_reset),
                    { onApply(TogglesPanelScreenIntent.Reset(row.key)) },
                    Modifier.testTag("reset:${row.key}"),
                    style = HbButtonStyle.Quiet,
                    enabled = enabled && row.isOverridden,
                )
            }
            ToggleControl(row, enabled, onApply)
        }
    }
}

@Composable
private fun ToggleControl(
    row: ToggleRowUi,
    enabled: Boolean,
    onApply: (TogglesPanelScreenIntent.Mutation) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val control = row.control) {
        is ToggleControlUi.Flag -> HbSwitch(
            control.isChecked,
            { onApply(TogglesPanelScreenIntent.SetFlag(row.key, it)) },
            row.key,
            modifier.testTag("flag:${row.key}"),
            enabled,
        )

        is ToggleControlUi.Choice -> HbFlowRow(modifier) {
            control.options.forEach { option ->
                HbButton(
                    option,
                    { onApply(TogglesPanelScreenIntent.SetChoice(row.key, option)) },
                    Modifier.testTag("choice:${row.key}:$option").semantics { selected = option == control.value },
                    style = if (option == control.value) HbButtonStyle.Primary else HbButtonStyle.Secondary,
                    enabled = enabled,
                )
            }
        }
    }
}
