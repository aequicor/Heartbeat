package io.aequicor.heartbeat.feature.autocontinue.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsIntent
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsModel
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsScreenState
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.ContextSettingsUi
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.context
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.Res
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_context
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_back
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_save_failed
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_continue_title
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_done
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_history
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_history_count
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_history_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_more
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_prompt
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_prompt_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_required
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_arguments
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_arguments_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_background
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_background_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_checklist
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_checklist_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_history
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_history_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_plan
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_plan_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_questions
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_questions_hint
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_tools
import io.aequicor.heartbeat.feature.autocontinue.impl.resources.auto_source_tools_hint
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Profile autopilot instructions and the context sources used to compose its next reply. */
@Composable
internal fun AutoContinueSettingsScreen(
    model: AutoContinueSettingsModel,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by produceState(model.current.value, model) {
        model.store.collect { states.collect { value = it } }
    }
    AutoContinueSettingsContent(state, remember(model) { model.store::intent }, onBack, modifier)
}

/** The editor scrolls independently; advanced context choices share the same stored values. */
@Composable
internal fun AutoContinueSettingsContent(
    state: AutoContinueSettingsScreenState,
    onIntent: (AutoContinueSettingsIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var isMoreOpen by remember { mutableStateOf(false) }
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("auto-continue-settings"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) Header(onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.xl,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                if (state.isSaveFailed) item { HbBanner(stringResource(Res.string.auto_continue_save_failed)) }
                item {
                    HbColumn(gap = HbTheme.spacing.m) {
                        HbText(stringResource(Res.string.auto_prompt), style = HbTheme.typography.label)
                        Hint(Res.string.auto_prompt_hint)
                        HbTextField(
                            state.settings.systemPrompt,
                            { onIntent(AutoContinueSettingsIntent.SetSystemPrompt(it)) },
                            Modifier.fillMaxWidth().height(
                                HbTheme.dimensions.autopilotPromptHeight,
                            ).testTag("auto-system-prompt"),
                            accessibleLabel = stringResource(Res.string.auto_prompt),
                            singleLine = false,
                            enabled = state.isLoaded,
                        )
                    }
                }
                item { HistorySize(state, onIntent) }
                item {
                    HbColumn(gap = HbTheme.spacing.l) {
                        HbText(stringResource(Res.string.auto_context), style = HbTheme.typography.label)
                        ContextSources(state, onIntent, isAdvanced = false)
                        HbButton(
                            stringResource(Res.string.auto_more),
                            { isMoreOpen = true },
                            enabled = state.isLoaded,
                            style = HbButtonStyle.Secondary,
                        )
                    }
                }
            }
        }
    }
    if (isMoreOpen) {
        HbDialog(
            stringResource(Res.string.auto_context),
            { isMoreOpen = false },
            isBackgroundBlurred = true,
            actions = { HbButton(stringResource(Res.string.auto_done), { isMoreOpen = false }) },
        ) {
            Hint(Res.string.auto_required)
            ContextSources(state, onIntent, isAdvanced = true)
        }
    }
}

@Composable
private fun Hint(text: StringResource) {
    HbText(stringResource(text), style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
}

@Composable
private fun HistorySize(state: AutoContinueSettingsScreenState, onIntent: (AutoContinueSettingsIntent) -> Unit) {
    var isExpanded by remember { mutableStateOf(false) }
    val settings = state.context
    val items = listOf(10, 20, 40).map {
        HbMenuItem(
            it.toString(),
            stringResource(Res.string.auto_history_count, it),
            isChecked = settings.historyMessages == it,
        )
    }.toImmutableList()
    HbColumn(gap = HbTheme.spacing.m) {
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.l) {
            HbText(stringResource(Res.string.auto_history), Modifier.weight(1f))
            HbMenuButton(
                HbIcons.ChevronDown,
                stringResource(Res.string.auto_history),
                items,
                isExpanded,
                { isExpanded = it },
                { id ->
                    isExpanded = false
                    id.toIntOrNull()?.takeIf { it in listOf(10, 20, 40) }?.let {
                        onIntent(AutoContinueSettingsIntent.SetContext(settings.copy(historyMessages = it)))
                    }
                },
                modifier = Modifier.weight(1f).background(HbTheme.surfaces.composerPill, HbTheme.shapes.small),
                enabled = state.isLoaded,
                label = stringResource(Res.string.auto_history_count, settings.historyMessages),
            )
        }
        Hint(Res.string.auto_history_hint)
    }
}

@Composable
private fun ContextSources(
    state: AutoContinueSettingsScreenState,
    onIntent: (AutoContinueSettingsIntent) -> Unit,
    isAdvanced: Boolean,
) {
    val c = state.context
    val change: (ContextSettingsUi) -> Unit = { onIntent(AutoContinueSettingsIntent.SetContext(it)) }
    HbColumn(gap = HbTheme.spacing.l) {
        Source(
            Res.string.auto_source_history,
            Res.string.auto_source_history_hint,
            c.isHistoryIncluded,
            state.isLoaded,
        ) { change(c.copy(isHistoryIncluded = it)) }
        Source(
            Res.string.auto_source_plan,
            Res.string.auto_source_plan_hint,
            c.isPlanIncluded,
            state.isLoaded,
        ) { change(c.copy(isPlanIncluded = it)) }
        if (isAdvanced) {
            Source(
                Res.string.auto_source_tools,
                Res.string.auto_source_tools_hint,
                c.isToolResultsIncluded,
                state.isLoaded,
            ) { change(c.copy(isToolResultsIncluded = it)) }
            Source(
                Res.string.auto_source_arguments,
                Res.string.auto_source_arguments_hint,
                c.isToolArgumentsIncluded,
                state.isLoaded,
            ) { change(c.copy(isToolArgumentsIncluded = it)) }
            Source(
                Res.string.auto_source_checklist,
                Res.string.auto_source_checklist_hint,
                c.isChecklistIncluded,
                state.isLoaded,
            ) { change(c.copy(isChecklistIncluded = it)) }
            Source(
                Res.string.auto_source_questions,
                Res.string.auto_source_questions_hint,
                c.isQuestionnairesIncluded,
                state.isLoaded,
            ) { change(c.copy(isQuestionnairesIncluded = it)) }
            Source(
                Res.string.auto_source_background,
                Res.string.auto_source_background_hint,
                c.isBackgroundResultsIncluded,
                state.isLoaded,
            ) { change(c.copy(isBackgroundResultsIncluded = it)) }
        }
    }
}

@Composable
private fun Source(
    label: StringResource,
    hint: StringResource,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.l) {
        HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.s) {
            HbText(stringResource(label))
            Hint(hint)
        }
        HbSwitch(checked, onChange, stringResource(label), enabled = enabled)
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
