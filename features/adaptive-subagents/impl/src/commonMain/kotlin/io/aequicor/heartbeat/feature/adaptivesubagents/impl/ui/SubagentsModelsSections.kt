package io.aequicor.heartbeat.feature.adaptivesubagents.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.DescriptionUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ModelEntryUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.PriorityUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ResearchFailureUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.StageUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.SubagentsScreenIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.SubagentsScreenState
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.TrialUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.Res
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_cancel
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_confirm_replace
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_confirm_text
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_confirm_title
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_dismiss
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_failure_busy
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_failure_failed
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_failure_invalid
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_failure_no_sources
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_failure_researcher
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_no_models
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_no_researcher
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_research
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_research_stop
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_models
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_models_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_trial
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_trial_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_stage_facts
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_stage_queued
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_stage_reading
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_stage_searching
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_stage_writing
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_status_borrowed
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_status_estimated
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_status_manual
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_status_researched
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_trial_default_effort
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_trial_empty
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_trial_option
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_trial_run
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_trial_task
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Enabled models with their description source, research progress and research actions. */
@Composable
internal fun ModelsSection(
    state: SubagentsScreenState,
    onIntent: (SubagentsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.subagents_section_models),
        modifier.testTag("adaptive-subagents-models"),
        description = stringResource(Res.string.subagents_section_models_hint),
    ) {
        state.researchFailure?.let { failure ->
            HbBanner(stringResource(failure.message()), Modifier.testTag("adaptive-subagents-research-failure")) {
                HbButton(
                    stringResource(Res.string.subagents_dismiss),
                    { onIntent(SubagentsScreenIntent.DismissResearchFailure) },
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            }
        }
        if (state.entries.isEmpty()) HbEmptyState(stringResource(Res.string.subagents_no_models))
        if (state.isResearchEnabled && !state.hasResearcher) {
            HbText(stringResource(Res.string.subagents_no_researcher), color = HbTheme.colors.textSecondary)
        }
        state.entries.forEachIndexed { index, entry ->
            key(entry.id) {
                if (index > 0) HbDivider()
                ModelRow(entry, state.isResearchEnabled && state.hasResearcher, onIntent)
            }
        }
    }
    state.entries.firstOrNull { it.id == state.confirming }?.let { ConfirmDialog(it, onIntent) }
}

@Composable
private fun ModelRow(
    entry: ModelEntryUi,
    canResearch: Boolean,
    onIntent: (SubagentsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = stringResource(entry.description.title())
    val progress = entry.stage?.let { stringResource(it.title(), entry.sources) }
    val description = progress ?: entry.ratings?.let {
        stringResource(
            Res.string.subagents_ratings,
            status,
            it,
        )
    } ?: status
    HbSettingsRow(
        "${entry.title} · ${entry.engine}",
        modifier.fillMaxWidth().testTag("adaptive-subagents-model-${entry.id}"),
        description = description,
    ) {
        if (entry.stage != null) {
            HbButton(
                stringResource(Res.string.subagents_research_stop),
                { onIntent(SubagentsScreenIntent.CancelResearch(entry.id)) },
                Modifier.testTag("adaptive-subagents-stop"),
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        } else if (canResearch) {
            HbButton(
                stringResource(Res.string.subagents_research),
                { onIntent(SubagentsScreenIntent.Research(entry.id)) },
                Modifier.testTag("adaptive-subagents-research"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    entry: ModelEntryUi,
    onIntent: (SubagentsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbDialog(
        stringResource(Res.string.subagents_confirm_title),
        { onIntent(SubagentsScreenIntent.CancelConfirm) },
        modifier.testTag("adaptive-subagents-confirm"),
        actions = {
            HbButton(
                stringResource(Res.string.subagents_cancel),
                { onIntent(SubagentsScreenIntent.CancelConfirm) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.subagents_confirm_replace),
                { onIntent(SubagentsScreenIntent.ConfirmResearch) },
                Modifier.testTag("adaptive-subagents-confirm-replace"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.subagents_confirm_text, entry.title))
    }
}

/** A task and priority in, the scored shortlist a subagent would get out. */
@Composable
internal fun TrialSection(trial: TrialUi, onIntent: (SubagentsScreenIntent) -> Unit, modifier: Modifier = Modifier) {
    HbSettingsSection(
        stringResource(Res.string.subagents_section_trial),
        modifier.testTag("adaptive-subagents-trial"),
        description = stringResource(Res.string.subagents_section_trial_hint),
    ) {
        val placeholder = stringResource(Res.string.subagents_trial_task)
        HbTextField(
            trial.task,
            { onIntent(SubagentsScreenIntent.ChangeTrialTask(it)) },
            Modifier.fillMaxWidth().testTag("adaptive-subagents-trial-task"),
            placeholder = placeholder,
        )
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            val labels = PriorityUi.entries.map { it.name to stringResource(it.title()) }.toImmutableList()
            ChoiceMenu(
                labels,
                trial.priority.name,
                stringResource(Res.string.subagents_section_trial),
                "adaptive-subagents-trial-priority",
                { onIntent(SubagentsScreenIntent.SelectTrialPriority(PriorityUi.valueOf(it))) },
            )
            HbButton(
                stringResource(Res.string.subagents_trial_run),
                { onIntent(SubagentsScreenIntent.RunTrial) },
                Modifier.testTag("adaptive-subagents-trial-run"),
                enabled = trial.task.isNotBlank() && !trial.isRunning,
                size = HbButtonSize.Small,
            )
        }
        if (trial.isTried && trial.options.isEmpty()) HbEmptyState(stringResource(Res.string.subagents_trial_empty))
        val defaultEffort = stringResource(Res.string.subagents_trial_default_effort)
        trial.options.forEach { option ->
            HbSettingsRow(
                option.title,
                Modifier.fillMaxWidth(),
                description = stringResource(
                    Res.string.subagents_trial_option,
                    option.effort ?: defaultEffort,
                    option.ratings,
                    option.score,
                ),
            )
        }
    }
}

private fun DescriptionUi.title(): StringResource = when (this) {
    DescriptionUi.Manual -> Res.string.subagents_status_manual
    DescriptionUi.Researched -> Res.string.subagents_status_researched
    DescriptionUi.Borrowed -> Res.string.subagents_status_borrowed
    DescriptionUi.Estimated -> Res.string.subagents_status_estimated
}

private fun StageUi.title(): StringResource = when (this) {
    StageUi.Queued -> Res.string.subagents_stage_queued
    StageUi.Facts -> Res.string.subagents_stage_facts
    StageUi.Searching -> Res.string.subagents_stage_searching
    StageUi.Reading -> Res.string.subagents_stage_reading
    StageUi.Writing -> Res.string.subagents_stage_writing
}

private fun ResearchFailureUi.message(): StringResource = when (this) {
    ResearchFailureUi.Busy -> Res.string.subagents_failure_busy
    ResearchFailureUi.NoSources -> Res.string.subagents_failure_no_sources
    ResearchFailureUi.ResearcherUnavailable -> Res.string.subagents_failure_researcher
    ResearchFailureUi.InvalidAnswer -> Res.string.subagents_failure_invalid
    ResearchFailureUi.Failed -> Res.string.subagents_failure_failed
}
