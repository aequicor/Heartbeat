package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation.ContextSettingsModel
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation.ContextSettingsScreenIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation.ContextSettingsScreenState
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation.StrategyUi
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation.ThresholdChoices
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.Res
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_auto
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_auto_hint
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_back
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_collapse_hint
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_collapse_reasoning
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_collapse_tools
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_save_failed
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_section_compaction
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_section_compaction_hint
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_section_feed
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_strategy
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_strategy_auto
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_strategy_native
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_strategy_rollover
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_threshold
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_threshold_value
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources.context_title
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Context settings of the profile. */
@Composable
internal fun ContextSettingsScreen(model: ContextSettingsModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(model.current.value, model) {
        model.store.collect { states.collect { value = it } }
    }
    ContextSettingsContent(state, model.store::intent, onBack, modifier)
}

/** Automatic compaction with its threshold and strategy, then how the chat feed shows reasoning and tools. */
@Composable
internal fun ContextSettingsContent(
    state: ContextSettingsScreenState,
    onIntent: (ContextSettingsScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("context-settings"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) ContextHeader(onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                item(key = "compaction") { CompactionSection(state, onIntent) }
                if (state.isSaveFailed) {
                    item(key = "error") {
                        HbBanner(stringResource(Res.string.context_save_failed), Modifier.testTag("context-error"))
                    }
                }
                item(key = "feed") { FeedSection(state, onIntent) }
            }
        }
    }
}

@Composable
private fun ContextHeader(onBack: () -> Unit) {
    HbPaneHeader(
        stringResource(Res.string.context_title),
        leadingInset = if (HbTheme.dimensions.isDesktop) HbTheme.spacing.m else HbTheme.dimensions.titlebarLeadingInset,
        navigation = {
            HbIconButton(
                HbIcons.ArrowLeft,
                stringResource(Res.string.context_back),
                onBack,
                Modifier.testTag("context-back"),
            )
        },
    )
}

@Composable
private fun CompactionSection(state: ContextSettingsScreenState, onIntent: (ContextSettingsScreenIntent) -> Unit) {
    val settings = state.settings
    val isEditable = state.isLoaded && settings.isAutoCompactionEnabled
    HbSettingsSection(
        stringResource(Res.string.context_section_compaction),
        description = stringResource(Res.string.context_section_compaction_hint),
    ) {
        SwitchRow(
            stringResource(Res.string.context_auto),
            stringResource(Res.string.context_auto_hint),
            settings.isAutoCompactionEnabled,
            state.isLoaded,
            "context-auto",
        ) { onIntent(ContextSettingsScreenIntent.SetAutoCompaction(it)) }
        HbText(stringResource(Res.string.context_threshold), color = HbTheme.colors.textSecondary)
        ChoiceGroup {
            ThresholdChoices.forEach { percent ->
                HbChoiceRow(
                    stringResource(Res.string.context_threshold_value, percent),
                    selected = settings.thresholdPercent == percent,
                    isMultiple = false,
                    onClick = { onIntent(ContextSettingsScreenIntent.SetThreshold(percent)) },
                    modifier = Modifier.fillMaxWidth().testTag("context-threshold-$percent"),
                    enabled = isEditable,
                )
            }
        }
        HbText(stringResource(Res.string.context_strategy), color = HbTheme.colors.textSecondary)
        ChoiceGroup {
            StrategyUi.entries.forEach { strategy ->
                HbChoiceRow(
                    stringResource(strategy.title()),
                    selected = settings.strategy == strategy,
                    isMultiple = false,
                    onClick = { onIntent(ContextSettingsScreenIntent.SetStrategy(strategy)) },
                    modifier = Modifier.fillMaxWidth().testTag("context-strategy-${strategy.name}"),
                    enabled = state.isLoaded,
                )
            }
        }
    }
}

@Composable
private fun FeedSection(state: ContextSettingsScreenState, onIntent: (ContextSettingsScreenIntent) -> Unit) {
    val hint = stringResource(Res.string.context_collapse_hint)
    HbSettingsSection(stringResource(Res.string.context_section_feed), description = hint) {
        SwitchRow(
            stringResource(Res.string.context_collapse_reasoning),
            null,
            state.settings.isReasoningCollapsed,
            state.isLoaded,
            "context-collapse-reasoning",
        ) { onIntent(ContextSettingsScreenIntent.SetReasoningCollapsed(it)) }
        SwitchRow(
            stringResource(Res.string.context_collapse_tools),
            null,
            state.settings.isToolCallsCollapsed,
            state.isLoaded,
            "context-collapse-tools",
        ) { onIntent(ContextSettingsScreenIntent.SetToolCallsCollapsed(it)) }
    }
}

/** Exclusive options announced by screen readers as one group with each option's position. */
@Composable
private fun ChoiceGroup(content: @Composable ColumnScope.() -> Unit) {
    HbColumn(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.xs, content = content)
}

@Composable
private fun SwitchRow(
    title: String,
    description: String?,
    isChecked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    HbSettingsRow(title, Modifier.fillMaxWidth().testTag("$tag-row"), description = description) {
        HbSwitch(isChecked, onChange, title, Modifier.testTag(tag), enabled = enabled)
    }
}

private fun StrategyUi.title() = when (this) {
    StrategyUi.Native -> Res.string.context_strategy_native
    StrategyUi.Rollover -> Res.string.context_strategy_rollover
    StrategyUi.Auto -> Res.string.context_strategy_auto
}

@Preview
@Composable
private fun ContextSettingsLightPreview() {
    HbTheme(darkTheme = false) { ContextSettingsContent(ContextSettingsScreenState(isLoaded = true), {}, {}) }
}

@Preview
@Composable
private fun ContextSettingsDarkPreview() {
    HbTheme(darkTheme = true) { ContextSettingsContent(ContextSettingsScreenState(isLoaded = true), {}, {}) }
}
