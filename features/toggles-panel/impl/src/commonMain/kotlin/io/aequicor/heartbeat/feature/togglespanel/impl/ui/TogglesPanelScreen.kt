package io.aequicor.heartbeat.feature.togglespanel.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSearchField
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.ToggleControlUi
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.ToggleDefaultUi
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.ToggleRowUi
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelModel
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelScreenIntent
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelScreenState
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.Res
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_back
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_default_value
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_description
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_dismiss
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_empty
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_error
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_intro_description
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_loading
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_off
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_on
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_override
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_reset
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_reset_all
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_retry
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_saving
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_search
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_search_clear
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_title
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_write_error
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun TogglesPanelScreen(model: TogglesPanelModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(TogglesPanelScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    TogglesPanelContent(state, model.store::intent, onBack, modifier)
}

/**
 * Feature flags as settings rows grouped by owning feature, with search, the declared default next to each
 * value and per-row and global reset. Inside the settings host [onBack] is `null` and only this content is drawn;
 * a standalone entry adds the shared header with "back".
 */
@Composable
internal fun TogglesPanelContent(
    state: TogglesPanelScreenState,
    onIntent: (TogglesPanelScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val introDescription = stringResource(Res.string.flags_intro_description)
    val groups = remember(state.rows, state.query, introDescription) {
        state.rows.filter { row -> matchesQuery(row, state.query.trim(), introDescription) }.groupBy { it.owner }
    }
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("toggles-panel"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.flags_title),
                leadingInset = HbTheme.dimensions.titlebarLeadingInset,
                navigation = {
                    HbIconButton(
                        HbIcons.ArrowLeft,
                        stringResource(Res.string.flags_back),
                        onBack,
                        Modifier.testTag("flags-back"),
                    )
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.fillMaxSize().widthIn(max = HbTheme.dimensions.settingsMaxWidth).testTag("flags-list"),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                item(key = "toolbar") { TogglesToolbar(state, onIntent) }
                panelStatus(state, groups.isEmpty(), onIntent)
                groups.forEach { (owner, rows) ->
                    item(key = "owner:$owner") {
                        ToggleGroup(owner, rows, enabled = !state.isSaving && !state.hasLoadError, onIntent)
                    }
                }
            }
        }
    }
}

private fun matchesQuery(row: ToggleRowUi, query: String, introDescription: String): Boolean {
    val description = if (row.key == INTRO_KEY) introDescription else row.description
    return row.key.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)
}

@Composable
private fun TogglesToolbar(
    state: TogglesPanelScreenState,
    onIntent: (TogglesPanelScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbText(
            stringResource(Res.string.flags_description),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            HbSearchField(
                state.query,
                { onIntent(TogglesPanelScreenIntent.Search(it)) },
                placeholder = stringResource(Res.string.flags_search),
                clearLabel = stringResource(Res.string.flags_search_clear),
                modifier = Modifier.weight(1f).testTag("flags-search"),
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
}

private fun LazyListScope.panelStatus(
    state: TogglesPanelScreenState,
    empty: Boolean,
    event: (TogglesPanelScreenIntent) -> Unit,
) {
    if (state.isLoading || state.isSaving) {
        item(key = "progress") {
            HbLoadingState(stringResource(if (state.isLoading) Res.string.flags_loading else Res.string.flags_saving))
        }
    }
    if (state.hasLoadError || state.hasWriteError) {
        item(key = "error") { PanelError(state.hasLoadError, state.isSaving, event) }
    }
    if (!state.isLoading && !state.hasLoadError && empty) {
        item(key = "empty") { HbEmptyState(stringResource(Res.string.flags_empty)) }
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
    HbBanner(stringResource(if (hasLoadError) Res.string.flags_error else Res.string.flags_write_error), modifier) {
        if (!hasLoadError) {
            HbButton(
                stringResource(Res.string.flags_dismiss),
                { event(TogglesPanelScreenIntent.DismissError) },
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
        HbButton(
            stringResource(Res.string.flags_retry),
            { event(retry) },
            Modifier.testTag("flags-retry"),
            style = HbButtonStyle.Secondary,
            enabled = !isSaving,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun ToggleGroup(
    owner: String,
    rows: List<ToggleRowUi>,
    enabled: Boolean,
    onApply: (TogglesPanelScreenIntent.Mutation) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(owner, modifier) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HbDivider()
            ToggleRow(row, enabled, onApply)
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
    val title = if (row.key == INTRO_KEY) stringResource(Res.string.flags_intro_description) else row.description
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.none) {
        HbSettingsRow(title, Modifier.testTag("row:${row.key}"), description = "${row.key} · ${sourceLabel(row)}") {
            if (row.isOverridden) {
                HbButton(
                    stringResource(Res.string.flags_reset),
                    { onApply(TogglesPanelScreenIntent.Reset(row.key)) },
                    Modifier.testTag("reset:${row.key}"),
                    style = HbButtonStyle.Ghost,
                    enabled = enabled,
                    size = HbButtonSize.Small,
                )
            }
            val control = row.control
            if (control is ToggleControlUi.Flag) {
                HbSwitch(
                    control.isChecked,
                    { onApply(TogglesPanelScreenIntent.SetFlag(row.key, it)) },
                    title,
                    Modifier.testTag("flag:${row.key}"),
                    enabled,
                )
            }
        }
        val control = row.control
        if (control is ToggleControlUi.Choice) ChoiceOptions(row.key, control, enabled, onApply)
    }
}

/** "Default: X" or "Local override · default: X" for the row's secondary line. */
@Composable
private fun sourceLabel(row: ToggleRowUi): String {
    val default = when (val value = row.default) {
        is ToggleDefaultUi.Flag -> stringResource(if (value.isEnabled) Res.string.flags_on else Res.string.flags_off)
        is ToggleDefaultUi.Choice -> value.value
    }
    return stringResource(if (row.isOverridden) Res.string.flags_override else Res.string.flags_default_value, default)
}

@Composable
private fun ChoiceOptions(
    key: String,
    control: ToggleControlUi.Choice,
    enabled: Boolean,
    onApply: (TogglesPanelScreenIntent.Mutation) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbFlowRow(
        modifier.fillMaxWidth().padding(start = HbTheme.spacing.m, bottom = HbTheme.spacing.s),
        gap = HbTheme.spacing.xs,
    ) {
        control.options.forEach { option ->
            HbButton(
                option,
                { onApply(TogglesPanelScreenIntent.SetChoice(key, option)) },
                Modifier.testTag("choice:$key:$option").semantics { selected = option == control.value },
                style = if (option == control.value) HbButtonStyle.Primary else HbButtonStyle.Ghost,
                enabled = enabled,
                size = HbButtonSize.Small,
            )
        }
    }
}

private const val INTRO_KEY = "welcome.cinematic_intro"

@Preview
@Composable
private fun TogglesPanelLightPreview() {
    HbTheme(darkTheme = false) {
        TogglesPanelContent(TogglesPanelScreenState(persistentListOf(), isLoading = false), {}, null)
    }
}

@Preview
@Composable
private fun TogglesPanelDarkPreview() {
    HbTheme(darkTheme = true) { TogglesPanelContent(TogglesPanelScreenState(isLoading = true), {}, {}) }
}
