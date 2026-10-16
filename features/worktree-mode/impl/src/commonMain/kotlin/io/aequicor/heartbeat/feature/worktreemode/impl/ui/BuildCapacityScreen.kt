package io.aequicor.heartbeat.feature.worktreemode.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.worktreemode.impl.presentation.BuildCapacityModel
import io.aequicor.heartbeat.feature.worktreemode.impl.presentation.BuildCapacityScreenIntent
import io.aequicor.heartbeat.feature.worktreemode.impl.presentation.BuildCapacityScreenState
import io.aequicor.heartbeat.feature.worktreemode.impl.presentation.ParallelBuildChoices
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.Res
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_auto
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_auto_hint
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_back
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_parallel
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_sandbox_hint
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_save_failed
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_section_capacity
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_section_capacity_hint
import io.aequicor.heartbeat.feature.worktreemode.impl.resources.builds_title
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Machine-wide build queue settings. */
@Composable
internal fun BuildCapacityScreen(model: BuildCapacityModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(BuildCapacityScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    BuildCapacityContent(state, model.store::intent, onBack, modifier)
}

/** The automatic estimate and fixed limits of parallel builds, with how sandboxes use build slots. */
@Composable
internal fun BuildCapacityContent(
    state: BuildCapacityScreenState,
    onIntent: (BuildCapacityScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("builds-settings"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) BuildsHeader(onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                item(key = "capacity") { CapacitySection(state, onIntent) }
                if (state.isSaveFailed) {
                    item(key = "error") {
                        HbBanner(stringResource(Res.string.builds_save_failed), Modifier.testTag("builds-error"))
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildsHeader(onBack: () -> Unit) {
    HbPaneHeader(
        stringResource(Res.string.builds_title),
        leadingInset = if (HbTheme.dimensions.isDesktop) HbTheme.spacing.m else HbTheme.dimensions.titlebarLeadingInset,
        navigation = {
            HbIconButton(
                HbIcons.ArrowLeft,
                stringResource(Res.string.builds_back),
                onBack,
                Modifier.testTag("builds-back"),
            )
        },
    )
}

@Composable
private fun CapacitySection(state: BuildCapacityScreenState, onIntent: (BuildCapacityScreenIntent) -> Unit) {
    HbSettingsSection(
        stringResource(Res.string.builds_section_capacity),
        description = stringResource(Res.string.builds_section_capacity_hint),
    ) {
        HbColumn(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.xs) {
            HbChoiceRow(
                stringResource(Res.string.builds_auto, state.automaticBuilds),
                selected = state.limit == null,
                isMultiple = false,
                onClick = { onIntent(BuildCapacityScreenIntent.SetLimit(null)) },
                modifier = Modifier.fillMaxWidth().testTag("builds-limit-auto"),
                enabled = state.isLoaded,
            )
            ParallelBuildChoices.forEach { builds ->
                HbChoiceRow(
                    stringResource(Res.string.builds_parallel, builds),
                    selected = state.limit == builds,
                    isMultiple = false,
                    onClick = { onIntent(BuildCapacityScreenIntent.SetLimit(builds)) },
                    modifier = Modifier.fillMaxWidth().testTag("builds-limit-$builds"),
                    enabled = state.isLoaded,
                )
            }
        }
        HbText(stringResource(Res.string.builds_auto_hint), color = HbTheme.colors.textSecondary)
        HbText(stringResource(Res.string.builds_sandbox_hint), color = HbTheme.colors.textSecondary)
    }
}

@Preview
@Composable
private fun BuildCapacityLightPreview() {
    HbTheme(darkTheme = false) {
        BuildCapacityContent(BuildCapacityScreenState(automaticBuilds = 3, isLoaded = true), {}, {})
    }
}

@Preview
@Composable
private fun BuildCapacityDarkPreview() {
    HbTheme(darkTheme = true) {
        BuildCapacityContent(BuildCapacityScreenState(automaticBuilds = 3, limit = 2, isLoaded = true), {}, {})
    }
}
