package io.aequicor.heartbeat.feature.autocontinue.impl.ui

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
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsIntent
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsModel
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsScreenState
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoTurnLimitChoices
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.ContinuerUi
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.CountdownChoices
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.DefaultModeUi
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.OptionCountChoices
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.Res
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_back
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_countdown
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_countdown_none
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_countdown_value
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_limit
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_mode_off
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_mode_suggest
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_model_chat
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_model_custom
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_model_default
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_options
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_save_failed
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_section_autopilot
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_section_model
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_section_model_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_section_new_chats
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_section_new_chats_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_section_suggestions
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_title
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Auto-continue settings of the profile. */
@Composable
internal fun AutoContinueSettingsScreen(
    model: AutoContinueSettingsModel,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by produceState(model.current.value, model) {
        model.store.collect { states.collect { value = it } }
    }
    AutoContinueSettingsContent(state, model.store::intent, onBack, modifier)
}

/** New chats, the continuer model, the autopilot and the suggestions. */
@Composable
internal fun AutoContinueSettingsContent(
    state: AutoContinueSettingsScreenState,
    onIntent: (AutoContinueSettingsIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("auto-continue-settings"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) Header(onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                if (state.isSaveFailed) {
                    item(key = "error") {
                        HbBanner(
                            stringResource(Res.string.auto_continue_save_failed),
                            Modifier.testTag("auto-continue-error"),
                        )
                    }
                }
                item(key = "new-chats") { NewChatsSection(state, onIntent) }
                item(key = "model") { ModelSection(state, onIntent) }
                item(key = "autopilot") { AutopilotSection(state, onIntent) }
                item(key = "suggestions") { SuggestionsSection(state, onIntent) }
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    HbPaneHeader(
        stringResource(Res.string.auto_continue_title),
        leadingInset = if (HbTheme.dimensions.isDesktop) HbTheme.spacing.m else HbTheme.dimensions.titlebarLeadingInset,
        navigation = {
            HbIconButton(
                HbIcons.ArrowLeft,
                stringResource(Res.string.auto_continue_back),
                onBack,
                Modifier.testTag("auto-continue-back"),
            )
        },
    )
}

@Composable
private fun NewChatsSection(state: AutoContinueSettingsScreenState, onIntent: (AutoContinueSettingsIntent) -> Unit) {
    HbSettingsSection(
        stringResource(Res.string.auto_continue_section_new_chats),
        description = stringResource(Res.string.auto_continue_section_new_chats_hint),
    ) {
        ChoiceGroup {
            listOf(
                DefaultModeUi.Off to Res.string.auto_continue_mode_off,
                DefaultModeUi.Suggest to Res.string.auto_continue_mode_suggest,
            ).forEach { (mode, label) ->
                Choice(
                    stringResource(label),
                    state.defaultMode == mode,
                    state.isLoaded,
                    "auto-continue-default-${mode.name}",
                ) { onIntent(AutoContinueSettingsIntent.SetDefaultMode(mode)) }
            }
        }
    }
}

@Composable
private fun ModelSection(state: AutoContinueSettingsScreenState, onIntent: (AutoContinueSettingsIntent) -> Unit) {
    HbSettingsSection(
        stringResource(Res.string.auto_continue_section_model),
        description = stringResource(Res.string.auto_continue_section_model_hint),
    ) {
        ChoiceGroup {
            Choice(
                stringResource(Res.string.auto_continue_model_chat),
                state.continuer == ContinuerUi.Chat,
                state.isLoaded,
                "auto-continue-model-chat",
            ) { onIntent(AutoContinueSettingsIntent.SetContinuer(ContinuerUi.Chat)) }
            state.profileDefaultModel?.let { model ->
                Choice(
                    stringResource(Res.string.auto_continue_model_default, model),
                    state.continuer == ContinuerUi.ProfileDefault,
                    state.isLoaded,
                    "auto-continue-model-default",
                ) { onIntent(AutoContinueSettingsIntent.SetContinuer(ContinuerUi.ProfileDefault)) }
            }
            state.customModel?.let { model ->
                Choice(
                    stringResource(Res.string.auto_continue_model_custom, model),
                    isSelected = true,
                    enabled = state.isLoaded,
                    tag = "auto-continue-model-custom",
                ) { onIntent(AutoContinueSettingsIntent.SetContinuer(ContinuerUi.Custom)) }
            }
        }
    }
}

@Composable
private fun AutopilotSection(state: AutoContinueSettingsScreenState, onIntent: (AutoContinueSettingsIntent) -> Unit) {
    HbSettingsSection(stringResource(Res.string.auto_continue_section_autopilot)) {
        HbText(stringResource(Res.string.auto_continue_limit), color = HbTheme.colors.textSecondary)
        ChoiceGroup {
            AutoTurnLimitChoices.forEach { limit ->
                Choice("$limit", state.autoTurnLimit == limit, state.isLoaded, "auto-continue-limit-$limit") {
                    onIntent(AutoContinueSettingsIntent.SetAutoTurnLimit(limit))
                }
            }
        }
        HbText(stringResource(Res.string.auto_continue_countdown), color = HbTheme.colors.textSecondary)
        ChoiceGroup {
            CountdownChoices.forEach { seconds ->
                val label = if (seconds == 0) {
                    stringResource(Res.string.auto_continue_countdown_none)
                } else {
                    stringResource(Res.string.auto_continue_countdown_value, seconds)
                }
                Choice(
                    label,
                    state.countdownSeconds == seconds,
                    state.isLoaded,
                    "auto-continue-countdown-$seconds",
                ) {
                    onIntent(AutoContinueSettingsIntent.SetCountdown(seconds))
                }
            }
        }
    }
}

@Composable
private fun SuggestionsSection(state: AutoContinueSettingsScreenState, onIntent: (AutoContinueSettingsIntent) -> Unit) {
    HbSettingsSection(stringResource(Res.string.auto_continue_section_suggestions)) {
        HbText(stringResource(Res.string.auto_continue_options), color = HbTheme.colors.textSecondary)
        ChoiceGroup {
            OptionCountChoices.forEach { count ->
                Choice("$count", state.optionCount == count, state.isLoaded, "auto-continue-options-$count") {
                    onIntent(AutoContinueSettingsIntent.SetOptionCount(count))
                }
            }
        }
    }
}

/** Exclusive options announced by screen readers as one group with each option's position. */
@Composable
private fun ChoiceGroup(content: @Composable ColumnScope.() -> Unit) {
    HbColumn(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.xs, content = content)
}

@Composable
private fun Choice(label: String, isSelected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    HbChoiceRow(
        label,
        selected = isSelected,
        isMultiple = false,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        enabled = enabled,
    )
}
